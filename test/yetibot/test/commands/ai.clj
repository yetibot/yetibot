(ns yetibot.test.commands.ai
  (:require [clj-http.client :as client]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.core.commands.collections]
            [yetibot.api.ai :as api]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as ai]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.util.public-result :as public-result]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.ai-execution :as execution]))

(def local-provider {:name "local" :url "http://localhost:11434/v1" :model "llama3"})
(def hosted-provider {:name "hosted" :url "https://example.com/v1" :key "secret" :model "chat"})
(def config {:providers [local-provider hosted-provider] :default-provider "hosted"})

(fact "empty discovery gives a useful status instead of a blank reply"
  (with-redefs [mcp/config (constantly {})
                tools/tool-catalog
                (fn [& _]
                  {:names [] :tools []
                   :discovery {:servers [{:server "search"
                                          :status "pending"}]}})]
    (let [result (ai/tools-cmd {:match ["tools" nil]})]
      (:result/value result)
      => (str "Tool discovery is refreshing in the background. "
              "No allowed tools are available yet.")
      (get-in result [:result/data :tools]) => [])))

(fact "inline-code tool names cannot reopen backticks or pipe syntax"
  (let [id "search/a`b|c"]
    (with-redefs [mcp/config (constantly {})
                  tools/tool-catalog
                  (fn [& _] {:names [id] :tools [{:id id :description ""}]})]
      (let [result (ai/tools-cmd {:match ["tools" nil]})
            row (first (:result/value result))]
        (count (filter #{\`} row)) => 2
        (string/includes? row "|") => false
        (get-in result [:result/data :tools 0 :id]) => id))))

(facts "leading options and multiline prompts"
  (ai/parse-prompt "hello\nworld --model literal")
  => {:prompt "hello\nworld --model literal"}
  (ai/parse-prompt "--model org/model --provider local explain this")
  => {:provider "local" :model "org/model" :prompt "explain this"}
  (ai/parse-prompt "--no-tools --provider local hello")
  => {:no-tools true :provider "local" :prompt "hello"}
  (ai/parse-prompt "-- --provider is part of my prompt")
  => {:prompt "--provider is part of my prompt"}
  (doseq [input ["" " " "--" "--provider" "--model x" "--unknown x hello"
                 "--provider --model x hi" "--model x --model y hi" "--no-tools"
                 "--no-tools --no-tools hello"]]
    (fact (ai/prompt-cmd {:match input}) => {:result/error ai/usage})))

(facts "tool budget flags are strict and fail before provider preparation"
  (ai/parse-prompt "--max-tool-calls 12 hello")
  => {:max-tool-calls 12 :prompt "hello"}
  (let [effects (atom 0)]
    (with-redefs [api/config (fn [] (swap! effects inc))]
      (doseq [flags ["--max-tool-calls 0" "--max-tool-calls 21"
                     "--max-tool-calls 1.5" "--max-tool-calls +2"
                     "--max-tool-calls 02"
                     "--max-tool-calls 2 --max-tool-calls 3"
                     "--no-tools --max-tool-calls 2"
                     "--max-tool-calls 2 --no-tools"]]
        (fact (ai/prompt-cmd {:match (str flags " PRIVATE-PROMPT")})
              => {:result/error ai/usage}))
      (fact @effects => 0))))

(fact "trace failures use safe value/data and stop downstream commands"
  (let [skip (atom 0)
        report {:reason :provider-failure
                :budget {:limit 6 :used 1 :remaining 5 :requested 0}
                :calls [{:call 1 :tool "yetibot_help" :arguments {}
                         :outcome :success :attempted true :confirmed true}]
                :omitted 0 :truncated false}
        result (ai/safely {:skip-next-n skip :next-cmds [:a :b]}
                         #(throw (ex-info "PRIVATE-CAUSE"
                                          {:ai/tool-failure report})))]
    (contains? result :result/error) => false
    (get-in result [:result/data :isError]) => true
    (get-in result [:result/data :tool-trace]) => report
    (string/includes? (:result/value result) "used=1") => true
    (string/includes? (:result/value result) "PRIVATE") => false
    @skip => 2))

(facts "registered command routing"
  (let [[_ commands] (hooks/find-sub-cmds "ai")]
    (fact
      (second (hooks/match-sub-cmds "providers" commands)) => #'ai/providers-cmd
      (second (hooks/match-sub-cmds "models" commands)) => #'ai/models-cmd
      (second (hooks/match-sub-cmds "models local" commands)) => #'ai/models-cmd
      (second (hooks/match-sub-cmds "servers" commands)) => #'ai/servers-cmd
      (second (hooks/match-sub-cmds "tools" commands)) => #'ai/tools-cmd
      (second (hooks/match-sub-cmds "tools search" commands)) => #'ai/tools-cmd
      (second (hooks/match-sub-cmds "hello\nworld" commands)) => #'ai/prompt-cmd)))

(facts "provider and model selection"
  (with-redefs [api/config (constantly config)
                context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                api/complete (fn [provider model messages] [(:name provider) model messages])]
    (ai/providers-cmd {})
    => {:result/value ["local" "hosted"]
        :result/data [{:name "local" :default_model "llama3"
                       :selected false :interface "chat-completions"}
                      {:name "hosted" :default_model "chat"
                       :selected true :interface "chat-completions"}]
        :result/public-data [{:name "local" :default_model "llama3"
                              :selected false :interface "chat-completions"}
                             {:name "hosted" :default_model "chat"
                              :selected true :interface "chat-completions"}]}
    (ai/prompt-cmd {:match "hello"})
    => ["hosted" "chat"
        (tools/host-messages [{:role "user" :content "hello"}])]
    (ai/prompt-cmd {:match "--provider local hello"})
    => ["local" "llama3"
        (tools/host-messages [{:role "user" :content "hello"}])]
    (ai/prompt-cmd {:match "--model other --provider local hello"})
    => ["local" "other"
        (tools/host-messages [{:role "user" :content "hello"}])]
    (ai/prompt-cmd {:match "--model other hello"})
    => {:result/error (str "Model catalogs are not ready, complete, or available. "
                          "Use --provider <name> with --model <exact-id>.")}
    (ai/prompt-cmd {:match "--provider missing hello"})
    => {:result/error "Unknown AI provider. Use ai providers to list available providers."})
  (with-redefs [api/config (constantly {:providers [(dissoc local-provider :model)]})]
    (ai/prompt-cmd {:match "hello"})
    => {:result/error "No default model configured for this provider. Use --model <model> or configure its model."})
  (api/provider (dissoc config :default-provider) nil) => local-provider)

(facts "models stay grouped, even when one provider fails"
  (with-redefs [api/config (constantly config)
                api/models (fn [provider]
                             (case (:name provider)
                               "local" ["llama3" "org/model"]
                               "hosted" (api/fail "Provider unavailable.")))]
    (ai/models-cmd {:match ["models" nil]})
    => {:result/data {"local" {:models ["llama3" "org/model"]}
                     "hosted" {:result/error "Provider unavailable."}}
        :result/value ["local: llama3, org/model"
                       "hosted: Provider unavailable."]
        :result/public-data
        {"local" {:status "available"
                  :models [{:id "llama3"} {:id "org/model"}]}
         "hosted" {:status "unavailable"}}}
    (ai/models-cmd {:match ["models local" "local"]})
    => {:result/data {"local" {:models ["llama3" "org/model"]}}
        :result/value ["local: llama3, org/model"]
        :result/public-data
        {"local" {:status "available"
                  :models [{:id "llama3"} {:id "org/model"}]}}}
    (ai/models-cmd {:match ["models missing" "missing"]})
    => {:result/error "Unknown AI provider. Use ai providers to list available providers."})
  (with-redefs [api/config (constantly config) api/models (constantly [])]
    (:result/value (ai/models-cmd {:match ["models local" "local"]}))
    => ["local: No models available."]))

(fact "model discovery keeps Cloudflare coverage and API interfaces visible"
  (let [catalog {:source :cloudflare-workers-ai
                 :coverage
                 "Workers AI only; not the third-party Gateway catalog"
                 :models [{:id "@cf/cloudflare/clef" :interface :decision
                           :task {:name "Text Generation"}}]}]
    (with-redefs [api/config (constantly {:providers [hosted-provider]})
                  api/models (constantly
                               (with-meta ["@cf/cloudflare/clef"]
                                 {:catalog catalog}))]
      (let [result (ai/models-cmd {:match ["models hosted" "hosted"]})]
        (get-in result [:result/data "hosted" :catalog]) => catalog
        (:result/value result)
        => [(str "hosted: " (:coverage catalog) "\n"
                 "@cf/cloudflare/clef [decision]")]))))

(facts "context is read for each request, without reusing previous prompts"
  (let [requests (atom [])]
    (with-redefs [api/config (constantly config)
                  client/post (fn [_ options]
                                (swap! requests conj (:form-params options))
                                {:status 200 :body {:choices [{:message {:content "answer"}}]}})]
      (:result/value (ai/prompt-cmd {:match "first"})) => "answer"
      (:result/value (ai/prompt-cmd {:match "second"})) => "answer"
      (mapv :messages @requests)
      => [(tools/host-messages [{:role "user" :content "first"}])
          (tools/host-messages [{:role "user" :content "second"}])])))

(fact "a failed thread read does not send a prompt"
  (let [sent? (atom false)]
    (with-redefs [api/config (constantly config)
                  context/messages (fn [& _] (api/fail "Could not read the thread."))
                  api/complete (fn [& _] (reset! sent? true))]
      (ai/prompt-cmd {:match "hello"}) => {:result/error "Could not read the thread."}
      @sent? => false)))

(fact "no-tools bypasses MCP discovery, and the default prompt uses MCP-aware completion"
  (with-redefs [api/config (constantly config)
                context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                api/complete (fn [& _] :plain)
                tools/complete (fn [& _] :tools)]
    (ai/prompt-cmd {:match "--no-tools hello"}) => :plain
    (ai/prompt-cmd {:match "hello"}) => :tools))

(fact "no-tools preserves host orientation without advertising tool access"
  (let [sent (atom nil)]
    (with-redefs [api/config (constantly config)
                  context/messages (fn [_ prompt]
                                     [{:role "user" :content prompt}])
                  tools/complete (fn [& _] (throw (AssertionError. "Tool loop")))
                  api/complete (fn [_ _ msgs] (reset! sent msgs) :plain)]
      (ai/prompt-cmd {:match "--no-tools yo"}) => :plain
      (:role (first @sent)) => "system"
      (boolean (re-find #"running inside Yetibot"
                       (:content (first @sent)))) => true
      (last @sent) => {:role "user" :content "yo"}
      (count @sent) => 2)))

(fact "no-tools also disables provider-native search"
  (with-redefs [api/config
                (constantly {:providers [(assoc hosted-provider
                                                :api "responses"
                                                :native-tools ["x_search"])]})
                context/messages (fn [_ prompt]
                                   [{:role "user" :content prompt}])
                api/complete (fn [provider _ _] (:native-tools provider))]
    (ai/prompt-cmd {:match "--no-tools hello"}) => nil))

(fact "tool listing preserves names and adds inert structured metadata"
  (let [server {:name "search" :url "https://secret.test/mcp"}
        calls (atom 0)
        tool {:name "web_search" :description "Search the web"
              :inputSchema {:type "object"}
              :session {:server server}}]
    (with-redefs [mcp/config (constantly {:servers [server]})
                  mcp/with-tools (fn [_ f]
                                   (swap! calls inc) (f [tool]))
                  api/chat (fn [& _] (throw (Exception. "no inference")))
                  mcp/call-tool (fn [& _]
                                  (throw (Exception. "no host calls")))]
      (let [result (ai/tools-cmd {:match ["tools" nil]})]
        (:result/value result) => ["`search/web_search` — Search the web"]
        (get-in result [:result/data :tools])
        => [{:id "search/web_search" :server "search" :name "web_search"
             :description "Search the web" :inputSchema {:type "object"}}]
        (get-in result [:result/data :instructions :runner])
        => tools/instructions
        (get-in result [:result/data :skills])
        => {:supported false :items []}
        @calls => 1))))

(facts "tool inspection commands handle configuration errors safely"
  (with-redefs [mcp/config (constantly nil)]
    (ai/servers-cmd {})
    => {:result/value [] :result/data [] :result/public-data []}
    (:result/value (ai/tools-cmd {:match ["tools" nil]}))
    => "No MCP servers configured."
    (get-in (ai/tools-cmd {:match ["tools" nil]}) [:result/data :skills])
    => {:supported false :items []})
  (with-redefs [mcp/config (fn [] (mcp/fail "Tool configuration is invalid."))]
    (ai/servers-cmd {}) => {:result/error "Tool configuration is invalid."}
    (ai/tools-cmd {:match ["tools" nil]}) => {:result/error "Tool configuration is invalid."}))

(def inspection-schema
  {:type "object" :required ["query"]
   :properties {"query" {"type" "string" "description" "Search terms"}
                "limit" {"type" "integer"}}})

(defn with-inspection [f]
  (let [servers [{:name "search" :url "https://search.test/mcp"}
                 {:name "other" :url "https://other.test/mcp"}]
        descriptions ["First tool" "Second tool"
                      "Find `cf expensive` | docs\n across lines" "Fourth tool"]
        advertised (conj
                     (mapv (fn [name description]
                             {:name name :description description
                              :inputSchema inspection-schema
                              :session {:server (first servers)}})
                           ["a" "b" "c" "d"] descriptions)
                     {:name "a" :description "Other first tool"
                      :inputSchema inspection-schema
                      :session {:server (second servers)}})
        effects (atom 0)
        forbidden (fn [& _] (swap! effects inc)
                    (throw (Exception. "inspection must not execute")))]
    (with-redefs
      [mcp/config
       (constantly {:servers servers
                    :toolsets [{:name "search" :kind "search"
                                :servers [{:name "search"} {:name "other"}]
                                :native-tools []}]})
       mcp/with-tools
       (fn [config callback]
         (let [allowed (set (map :name (:servers config)))]
           (callback (filterv #(allowed (get-in % [:session :server :name]))
                              advertised))))
       api/chat forbidden api/complete forbidden mcp/call-tool forbidden
       client/get forbidden client/post forbidden]
      (f)
      (fact @effects => 0))))

(facts "default summaries are short, one-line and keep IDs first"
  (with-inspection
    (fn []
      (:result/value (ai/tools-cmd {:match ["tools search" "search"]}))
      => ["`search/a` — First tool" "`search/b` — Second tool"
          "`search/c` — Find ’cf expensive’ ¦ docs across lines"
          "`search/d` — Fourth tool"])))

(fact "tool data is a small reviewed projection, not the structured catalog"
  (with-inspection
    (fn []
      (let [captured (atom [])
            listing (ai/tools-cmd {:match ["tools search" "search"]})
            detail (ai/tools-cmd {:match ["tools search/b" "search/b"]})]
        (:result/public-data listing)
        => [{:id "search/a" :summary "First tool"}
            {:id "search/b" :summary "Second tool"}
            {:id "search/c" :summary "Find `cf expensive` | docs across lines"}
            {:id "search/d" :summary "Fourth tool"}]
        (:result/public-data detail)
        => {:id "search/b" :summary "Second tool"
            :parameters [{:name "query" :type "string" :required true}
                         {:name "limit" :type "integer" :required false}]}
        (get-in detail [:result/data :inputSchema]) => inspection-schema
        (binding [public-result/*capture* #(swap! captured conj %)]
          (public-result/observe! listing)
          (public-result/observe! detail))
        (mapv :public-data? @captured) => [true true]
        (mapv :public-data @captured)
        => (mapv :result/public-data [listing detail])
        (:result/public-data
          (ai/tools-cmd {:match ["tools missing" "missing"]})) => nil))))

(fact "tool snapshots exclude manuals, raw schemas and provider extras"
  (let [entry {:id "search/read" :description "Read public information.\n\nMANUAL-BODY"
               :private "PROVIDER-PRIVATE" :headers {"key" "HEADER-PRIVATE"}
               :inputSchema {:type "object"
                             :properties {"query" {:type "string"
                                                   :description "PARAM-DOC"
                                                   :default "RAW-DEFAULT"
                                                   :enum ["RAW-ENUM"]}
                                          "maybe" {:type ["string" "null"]}
                                          "opaque" {:type {:private "RAW-TYPE"}}}}}]
    (with-redefs [mcp/config (constantly {})
                  tools/server-names (constantly ["search"])
                  tools/tool-catalog (fn [& _] {:tools [entry]
                                                :private "CATALOG-PRIVATE"})]
      (let [listing (ai/tools-cmd {:match ["tools" nil]})
            detail (ai/tools-cmd {:match ["tools search/read" "search/read"]})]
        (:result/public-data listing)
        => [{:id "search/read" :summary "Read public information."}]
        (:result/public-data detail)
        => {:id "search/read" :summary "Read public information."
            :parameters [{:name "maybe" :type "string | null" :required false}
                         {:name "opaque" :type "any" :required false}
                         {:name "query" :type "string" :required false}]}
        (:result/data detail) => entry))))

(fact "tool data keeps credential and mention redaction"
  (with-redefs [mcp/config
                (constantly {:servers [{:name "s" :url "https://s.test/mcp"
                                        :headers {"X-Api-Key" "private token"}}]})
                tools/server-names (constantly ["s"])
                tools/tool-catalog
                (fn [& _] {:tools [{:id "s/t" :name "t"
                                   :description "Find private token for @everyone."
                                   :inputSchema {:properties
                                                 {"private token" {:type "string"}}}}]})]
    (let [result (ai/tools-cmd {:match ["tools s/t" "s/t"]})
          data (pr-str (:result/public-data result))]
      (string/includes? data "[redacted]") => true
      (string/includes? data "private token") => false
      (string/includes? data "@everyone") => false)))

(fact "trailing whitespace still selects the default list"
  (with-inspection
    (fn []
      (count (:result/value
               (ai/tools-cmd {:match ["tools   " " "]}))) => 5)))

(facts "tool details accept IDs, unique names and decorated list rows"
  (with-inspection
    (fn []
      (doseq [query ["search/b" "b" "search/b — Second tool"
                     "`search/b`" "`search/b` — Second tool"]]
        (let [result (ai/tools-cmd {:match [(str "tools " query) query]})]
          (fact (:result/value result)
                => (str "search/b\nSecond tool\nInputs:\n"
                        "  query (required): string — Search terms\n"
                        "  limit: integer"))
          (fact (:result/data result)
                => {:id "search/b" :server "search" :name "b"
                    :description "Second tool"
                    :inputSchema inspection-schema})))
      (:result/error (ai/tools-cmd {:match ["tools a" "a"]}))
      => "Tool name is ambiguous. Use its server/name ID."
      (:result/error (ai/tools-cmd {:match ["tools search/missing"
                                          "search/missing"]}))
      => (str "Unknown or unavailable AI tool or server. "
              "Use ai tools to list tools."))))

(fact "real tail and xargs pipeline expands summary rows into tool details"
  (with-inspection
    (fn []
      (doseq [[command ids]
              [["ai tools search | tail 3 | xargs ai tools"
                ["search/b" "search/c" "search/d"]]
               ["ai tools | tail 3 | xargs ai tools"
                ["search/c" "search/d" "other/a"]]]]
        (let [result (execution/run-command execution/request command)
              values (:value result)]
          (fact (mapv #(first (string/split-lines %)) values) => ids)
          (fact (every? #(string/includes? % "query (required): string") values)
                => true)
          (fact (mapv :id (:data result)) => ids))))))

(fact "collection pipelines expose only corresponding final reviewed tool data"
  (with-inspection
    (fn []
      (with-redefs [clojure.core/rand-int (constantly 1)]
        (doseq [[pipeline ids detail?]
                [["random" ["search/b"] false]
                 ["head" ["search/a"] false]
                 ["tail 2" ["search/c" "search/d"] false]
                 ["reverse | head" ["search/d"] false]
                 ["grep Second" ["search/b"] false]
                 ["random | ai tools" ["search/b"] true]
                 ["tail 3 | xargs ai tools"
                  ["search/b" "search/c" "search/d"] true]]]
          (let [seen (atom [])
                result (binding [public-result/*capture* #(swap! seen conj %)]
                         (execution/run-command execution/request
                           (str "ai tools search | " pipeline)))
                snapshots (filterv :public-data? @seen)
                data (:public-data (first snapshots))
                entries (if (map? data) [data] data)]
            (fact (:error result) => nil)
            (fact (count snapshots) => 1)
            (fact (mapv :id entries) => ids)
            (fact (every? #(= detail? (contains? % :parameters)) entries)
                  => true)
            (fact (string/includes? (pr-str data) "inputSchema") => false)))))))

(fact "restricted toolset pipelines retain the trusted caller"
  (with-inspection
    (fn []
      (let [settings (-> (mcp/config)
                         (assoc-in [:toolsets 0 :users] ["U1"])
                         (assoc-in [:toolsets 0 :adapters] ["slack"]))
            denied (-> execution/request
                       (assoc-in [:user :id] "U2")
                       (assoc-in [:chat-source :raw-event :user] "U2"))]
        (with-redefs [mcp/config (constantly settings)]
          (let [result (execution/run-command execution/request
                         "ai tools | tail 3 | xargs ai tools")]
            (mapv #(some-> % string/split-lines first) (:value result))
            => ["search/c" "search/d" "other/a"]
            (mapv :id (:data result))
            => ["search/c" "search/d" "other/a"])
          (boolean (re-find #"Unknown or unavailable AI tool or server"
                     (str (:error (execution/run-command denied
                                    "ai tools search"))))) => true)))))

(fact "summaries keep full prose and neutralize chat mentions, not raw metadata"
  (with-inspection
    (fn []
      (let [description (str " @everyone <@123>\n"
                             (string/join (repeat 200 "x")))
            tool {:name "brief" :description description
                  :inputSchema inspection-schema
                  :session {:server {:name "search"}}}]
        (with-redefs [mcp/with-tools (fn [_ f] (f [tool]))]
          (let [result (ai/tools-cmd {:match ["tools" nil]})
                row (first (:result/value result))]
            (string/ends-with? row (string/join (repeat 200 "x"))) => true
            (string/ends-with? row "…") => false
            (string/includes? row "\n") => false
            (string/includes? row "@everyone") => false
            (string/includes? row "<@123>") => false
            (get-in result [:result/data :tools 0 :description])
            => description))))))

(fact "formatting redacts reconstructed credentials before display"
  (with-inspection
    (fn []
      (let [tool {:name "safe" :inputSchema inspection-schema
                  :description (str (string/join (repeat 115 "x"))
                                    "private\ntoken suffix")
                  :session {:server {:name "search"}}}]
        (with-redefs
          [mcp/config
           (constantly {:servers [{:name "search" :url "https://search.test/mcp"
                                   :headers {"X-Api-Key" "private token"}}]})
           mcp/with-tools (fn [_ f] (f [tool]))]
          (let [row (first (:result/value
                             (ai/tools-cmd {:match ["tools" nil]})))]
            (string/includes? row "priv") => false
            (string/includes? row "[red") => true))))))

(fact "real Cloudflare docs stay out of the overview and keep formatting in details"
  (let [description (slurp "test/fixtures/mcp_cloudflare_search_description.md")
        entry {:id "cloudflare-read/search" :name "search"
               :description description :inputSchema inspection-schema}]
    (with-redefs [mcp/config (constantly {})
                  tools/server-names (constantly ["cloudflare-read"])
                  tools/tool-catalog (fn [& _] {:tools [entry]})]
      (let [overview (ai/tools-cmd {:match ["tools" nil]})
            detail (ai/tools-cmd {:match ["tools" "cloudflare-read/search"]})]
        (first (:result/value overview))
        => "`cloudflare-read/search` — Search the Cloudflare OpenAPI spec."
        (get-in overview [:result/data :tools 0 :description]) => description
        (string/includes? (:result/value detail) description) => true
        (string/includes? (:result/value detail)
                          "\n\nTypes:\n\ninterface OperationInfo {") => true
        (string/includes? (:result/value detail) "All $refs") => true
        (string/includes? (:result/value detail) "\"x-api-token-group\"") => true))))

(fact "a machine-only introduction is replaced by a docs pointer, not clipped code"
  (let [description (str "```typescript\n" (string/join (repeat 1000 "x"))
                         "\n```\nDESCRIPTION-END")
        entry {:id "server/tool" :name "tool" :description description}]
    (with-redefs [mcp/config (constantly {})
                  tools/server-names (constantly ["server"])
                  tools/tool-catalog (fn [& _] {:tools [entry]})]
      (first (:result/value (ai/tools-cmd {:match ["tools" nil]})))
      => "`server/tool` — Full documentation: ai tools server/tool."
      (string/includes? (:result/value
                          (ai/tools-cmd {:match ["tools" "server/tool"]}))
                        description) => true)))

(fact "formatted docs still redact credentials reconstructed across whitespace"
  (with-redefs [mcp/config
                (constantly {:servers [{:name "s" :url "https://s.test/mcp"
                                        :headers {"X-Api-Key" "private token"}}]})
                tools/server-names (constantly ["s"])
                tools/tool-catalog
                (fn [& _] {:tools [{:id "s/t" :name "t"
                                   :description "Docs\n\nprivate\ntoken\n\n@everyone"}]})]
    (let [text (:result/value (ai/tools-cmd {:match ["tools" "s/t"]}))]
      (string/includes? text "[redacted]") => true
      (string/includes? text "private") => false
      (string/includes? text "@everyone") => false)))

(fact "full descriptions and parameter help are reviewed for frozen text paging"
  (with-inspection
    (fn []
      (let [description (str (string/join (repeat 600 "Full 😀 description. "))
                             "DESCRIPTION-END")
            parameter (str (string/join (repeat 100 "parameter help ")) "PARAM-END")
            tool {:name "long" :description description
                  :session {:server {:name "search"}}
                  :inputSchema {:type "object" :properties
                                {:query {:type "string" :description parameter}}}}
            captured (atom [])]
        (with-redefs [mcp/with-tools (fn [_ f] (f [tool]))]
          (binding [public-result/*capture* #(swap! captured conj %)]
            (doseq [selector [nil "search/long"]]
              (let [result (ai/tools-cmd {:match ["tools" selector]})
                    value (:result/value result)
                    text (if (string? value) value (string/join "\n" value))]
                (fact (string/includes? text "DESCRIPTION-END")
                      => (boolean selector))
                (when-not selector
                  (fact (string/includes? text "Full 😀 description.") => true))
                (public-result/observe! result)
                (fact (:public-text (last @captured)) => text)
                (when selector
                  (fact (string/includes? text "PARAM-END") => true))))))))))

(fact "detail explains enum choices and conditional required fields"
  (with-inspection
    (fn []
      (let [tool {:name "ask" :description "Text delegation"
                  :session {:server {:name "search"}}
                  :inputSchema
                  {:type "object" :required ["action"]
                   :properties {:action {:type "string" :enum ["ask" "models"]}
                                :prompt {:type "string"}}
                   :oneOf [{:properties {:action {:enum ["ask"]}}
                            :required ["action" "prompt"]}
                           {:properties {:action {:enum ["models"]}}
                            :required ["action"]}]}}]
        (with-redefs [mcp/with-tools (fn [_ f] (f [tool]))]
          (:result/value (ai/tools-cmd {:match ["tools search/ask"
                                               "search/ask"]}))
          => (str "search/ask\nText delegation\nInputs:\n"
                  "  action (required): string [ask, models]\n"
                  "  prompt: string\n"
                  "Required alternatives: ask: action, prompt; "
                  "models: action"))))))
