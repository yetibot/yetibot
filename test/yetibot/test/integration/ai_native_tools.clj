(ns yetibot.test.integration.ai-native-tools
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts => contains]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai]
            [yetibot.commands.grok]
            [yetibot.core.config :as settings]
            [yetibot.core.db.history :as history]
            [yetibot.core.db.util :as db]
            [yetibot.core.models.help :as help]
            [yetibot.core.commands.grokimage :as gi]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.util.http :as http]
            [yetibot.core.webapp.routes.images :as images]
            [yetibot.models.imgflip :as imgflip]
            [yetibot.core.models.history :as stored-history]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.test.support.mcp :as support]))

(def toolset
  {:name "yetibot_read" :kind "search" :servers []
   :native-tools ["help" "history" "history_count"]
   :users ["U1"] :adapters ["slack"]
   :providers [{:name "grok" :models ["grok-4.7"]}]})
(def config {:servers [] :toolsets [toolset] :max-tool-calls 4})
(def implicit-config (update-in config [:toolsets 0] dissoc :native-tools))
(def provider {:name "grok" :url "http://localhost/v1"})
(def messages [{:role "user" :content "Find the launch discussion."}])

(defn error-message [f]
  (try (f) (catch Exception e (.getMessage e))))

(defn with-history [f]
  (let [config (assoc (db/config) :table
                      {:prefix (str "native_" (System/nanoTime) "_")})]
    (with-redefs [db/config (constantly config)]
      (let [table (db/qualified-table-name "history")]
        (try
          (jdbc/db-do-commands
            (:url config)
            (jdbc/create-table-ddl
              table (get history/schema :schema/specs)
              {:entities #(string/replace (name %) "-" "_")}))
          (doseq [i (range 25)]
            (history/create
              {:body (str "launch " i) :user-name "alice"
               :chat-source-adapter "\"workspace\""
               :chat-source-room "private" :is-private true
               :created-at (java.sql.Timestamp. 1000)}))
          (doseq [[adapter room body]
                  [["\"other\"" "private" "other-connection-secret"]
                   ["\"workspace\"" "elsewhere" "other-channel-secret"]
                   ["\"workspace\"" "C1" "raw-id-is-not-storage-room"]]]
            (history/create {:body body :chat-source-adapter adapter
                             :chat-source-room room :is-private true}))
          (f)
          (finally
            (jdbc/db-do-commands (:url config) (str "DROP TABLE " table))))))))

(defn run-calls
  "Exercise shared validation/execution; only replace the model boundary."
  [settings calls & [request]]
  (execution/in-event (or request execution/request) "ai find launch"
    (fn [request]
      (let [sent (atom [])]
        (with-redefs [mcp/config (constantly settings)
                      ai/chat
                      (fn [_ _ msgs options]
                        (swap! sent conj {:messages msgs :options options})
                        {:choices
                         [{:message
                           (if (= 1 (count @sent))
                             {:role "assistant"
                              :tool_calls
                              (mapv (fn [i [name args]]
                                      {:id (str "call-" i) :type "function"
                                       :function
                                       {:name (str "yetibot_" name)
                                        :arguments
                                        (json/generate-string args)}})
                                    (range) calls)}
                             {:role "assistant" :content "Done."})}]})]
          (let [result (tools/complete
                         provider "grok-4.7" messages
                         {:actor (policy/actor request)})]
            {:result result :sent @sent
             :replies (mapv #(if (string/starts-with? (:content %)
                                                     "This tool was not run")
                              {:skipped true :reason (:content %)}
                              (json/parse-string (:content %) true))
                            (filter #(= "tool" (:role %))
                                    (:messages (last @sent))))}))))))

(facts "native discovery is explicit, visible only to authorized actors"
  (execution/in-event execution/request "ai tools"
    (fn [request]
      (with-redefs [mcp/config (constantly config)]
        (fact (tools/server-names (policy/actor request)) => ["yetibot"])
        (fact (tools/tool-names "yetibot" (policy/actor request))
              => ["yetibot/help" "yetibot/history" "yetibot/history_count"])
        (fact (tools/server-names nil) => [])))))

(facts "built-ins appear alongside legacy MCP tools without an opt-in toolset"
  (execution/in-event execution/request "ai tools"
    (fn [request]
      (let [actor (policy/actor request)
            remote {:name "search" :session {:server {:name "exa"}}}]
        (doseq [settings [nil {:servers []}
                          {:servers [{:name "exa" :tools ["search"]}]}]]
          (with-redefs [mcp/config (constantly settings)
                        mcp/with-tools
                        (fn [cfg f] (f (if (seq (:servers cfg)) [remote] [])))]
            (fact (tools/tool-names "yetibot" actor)
                  => ["yetibot/help" "yetibot/history"
                      "yetibot/history_count"])
            (fact (tools/tool-names nil actor)
                  => (vec (concat (when (seq (:servers settings))
                                    ["exa/search"])
                                  ["yetibot/help" "yetibot/history"
                                   "yetibot/history_count"])))))))))

(facts "implicit built-ins respect named policy and explicit opt-out"
  (execution/in-event execution/request "ai tools"
    (fn [request]
      (let [actor (policy/actor request)
            defaults implicit-config]
        (with-redefs [mcp/config (constantly defaults)]
          (fact (tools/tool-names "yetibot" actor)
                => ["yetibot/help" "yetibot/history"
                    "yetibot/history_count"])
          (fact (tools/server-names (assoc actor :user "U2")) => []))
        (with-redefs [mcp/config
                      (constantly (assoc-in config [:toolsets 0 :native-tools]
                                            []))]
          (fact (tools/server-names actor) => []))))))

(facts "default history keeps current-room scope without native configuration"
  (with-history
    (fn []
      (let [result (run-calls nil [["history" {"limit" 2}]
                                   ["history_count" {}]])]
        (fact (mapv :body (get-in result
                                 [:replies 0 :structuredContent :messages]))
              => ["launch 23" "launch 24"])
        (fact (get-in result [:replies 1 :structuredContent :count]) => 25)))))

(fact "text-only providers still work without MCP or explicit native tools"
  (execution/in-event execution/request "ai explain"
    (fn [request]
      (with-redefs [mcp/config (constantly nil)
                    ai/complete (fn [& _] {:result/value "Plain answer."})
                    ai/chat (fn [& _] (throw (AssertionError. "Tool loop")))]
        (tools/complete (assoc provider :tool-calling false)
                        "text-only" messages {:actor (policy/actor request)})
        => {:result/value "Plain answer."}))))

(facts "real PostgreSQL history is bounded, tied by id, and current-room only"
  (with-history
    (fn []
      (let [result (run-calls config [["history" {"query" "launch"
                                                 "limit" 2}]
                                     ["history_count" {"query" "launch"}]])
            page (get-in result [:replies 0 :structuredContent])
            next-page (run-calls config
                       [["history" {"limit" 2
                                    "before_id" (:next_before page)}]])]
        (fact (mapv :body (:messages page)) => ["launch 23" "launch 24"])
        (fact (:has_more page) => true)
        (fact (:untrusted page) => true)
        (fact (mapv :body (get-in next-page
                                 [:replies 0 :structuredContent :messages]))
              => ["launch 21" "launch 22"])
        (fact (get-in result [:replies 1 :structuredContent :count]) => 25)
        (fact (get-in result [:result :result/data :mcp :tool-calls]) => 2)
        (fact (some #(string/includes? (json/generate-string result) %)
                    ["other-channel-secret" "other-connection-secret"
                     "raw-id-is-not-storage-room"]) => nil)
        (fact (get-in (run-calls config
                       [["history" {"before_id" 28}]])
                      [:replies 0 :structuredContent :messages]) => [])
        (fact (get-in (run-calls config
                       [["history_count" {"query" "%_"}]])
                      [:replies 0 :structuredContent :count]) => 0)))))

(facts "invalid native arguments reject the whole batch before any DB access"
  (doseq [args [{"room" "elsewhere"} {"limit" 0} {"limit" 21}
               {"limit" "2"} {"before_id" -1} {"before_id" 1.5}
               {"query" ""} {"query" (apply str (repeat 201 "x"))}]]
    (with-redefs [jdbc/query (fn [& _]
                               (throw (AssertionError. "Database accessed")))]
      (fact (error-message #(run-calls config
                             [["history_count" {}] ["history" args]]))
            => "Invalid arguments for native Yetibot tool."))))

(facts "help reads docs without executing a command or leaking alias bodies"
  (with-redefs [help/get-docs (constantly {"echo" #{"echo <text> # repeat"}
                                         "history" #{"history # chat history"}})
                help/get-alias-docs (constantly {"secret-alias"
                                                #{"secret-alias TOKEN"}})]
    (let [result (run-calls config [["help" {}]
                                   ["help" {"topic" "echo"}]])]
      (fact (get-in result [:replies 0 :structuredContent :topics])
            => ["echo" "history"])
      (fact (get-in result [:replies 1 :structuredContent :docs])
            => ["echo <text> # repeat"])
      (fact (string/includes? (json/generate-string result) "TOKEN")
            => false))))

(facts "native read batches retain shared duplicate and call-budget checks"
  (with-redefs [jdbc/query (fn [& _]
                             (throw (AssertionError. "Database accessed")))]
    (fact (error-message #(run-calls config
                           [["history_count" {}] ["history_count" {}]]))
          => (contains "repeat a tool call"))
    (fact (error-message #(run-calls (assoc config :max-tool-calls 1)
                           [["history" {}] ["history_count" {}]]))
          => (contains "tool-call limit"))))

(facts "explicit native overrides still validate their names and access policy"
  (fact (error-message #(toolsets/validate!
                         {:servers [] :native-tools ["history"]}))
        => (contains "Invalid AI toolsets"))
  (doseq [t [(dissoc toolset :users)
             (dissoc toolset :adapters) (dissoc toolset :providers)
             (assoc toolset :native-tools ["exec"])
             (assoc toolset :native-tools ["history" "history"])
             (assoc toolset :adapters ["http"])
             (assoc toolset :servers nil)]]
    (fact (error-message #(toolsets/validate! (assoc config :toolsets [t])))
          => (str "Invalid AI toolsets. Check names, references "
                  "and access policy."))))

(facts "implicit native tools enforce the selected toolset's policy"
  (execution/in-event execution/request "ai find launch"
    (fn [request]
      (let [actor (policy/actor request)]
        (with-redefs [mcp/config (constantly implicit-config)
                      ai/chat (fn [& _]
                                (throw (AssertionError. "Model called")))
                      jdbc/query (fn [& _]
                                   (throw (AssertionError. "DB called")))]
          (doseq [[a p model]
                  [[nil provider "grok-4.7"]
                   [(assoc actor :user "U2") provider "grok-4.7"]
                   [actor {:name "other"} "grok-4.7"]
                   [actor provider "forbidden"]]]
            (fact (string? (error-message
                             #(tools/complete p model messages {:actor a})))
                  => true)))))))

(facts "identity/model policy runs before model or database access"
  (execution/in-event execution/request "ai find launch"
    (fn [request]
      (let [actor (policy/actor request)]
        (with-redefs [mcp/config (constantly config)
                      ai/chat (fn [& _]
                                (throw (AssertionError. "Model called")))
                      jdbc/query (fn [& _]
                                   (throw (AssertionError. "DB called")))]
          (doseq [[a p model]
                  [[nil provider "grok-4.7"]
                   [(assoc actor :user "U2") provider "grok-4.7"]
                   [(assoc actor :conversation "workspace/C2")
                    provider "grok-4.7"]
                   [actor {:name "other"} "grok-4.7"]
                   [actor provider "other"]]]
            (fact (string? (error-message
                             #(tools/complete p model messages {:actor a})))
                  => true)))))))

(fact "a claimed actor without adapter provenance cannot enable native tools"
  (with-redefs [mcp/config (constantly config)]
    (error-message
      #(tools/complete provider "grok-4.7" messages
         {:actor {:adapter "slack" :connection "workspace" :user "U1"
                  :conversation "workspace/C1" :thread "root"}}))
    => "Native Yetibot tools require a trusted chat request."))

(facts "escaped history and help remain valid bounded JSON, with real cursors"
  (with-history
    (fn []
      (doseq [i (range 5)]
        (history/create
          {:body (str "large " i (apply str (repeat 3000 \u0001)))
           :user-name (apply str (repeat 300 "u"))
           :chat-source-adapter "\"workspace\""
           :chat-source-room "private" :created-at (java.sql.Timestamp. 2000)}))
      (let [result (run-calls config [["history" {"limit" 20}]])
            page (get-in result [:replies 0 :structuredContent])
            next-page (run-calls config
                        [["history" {"before_id" (:next_before page)}]])]
        (fact (count (:messages page)) => 1)
        (fact (:truncated page) => true)
        (fact (get-in page [:messages 0 :body-truncated]) => true)
        (fact (get-in page [:messages 0 :user-name-truncated]) => true)
        (fact (count (get-in page [:messages 0 :body])) => 1000)
        (fact (<= (count (json/generate-string (first (:replies result))))
                  12200) => true)
        (fact (string/starts-with?
                (get-in next-page [:replies 0 :structuredContent :messages 0
                                  :body]) "large 3") => true))))
  (with-redefs [help/get-docs
                (constantly {"long" #{(apply str (repeat 10000 \u0001))}})]
    (let [reply (first (:replies (run-calls config
                                  [["help" {"topic" "long"}]])))]
      (fact (get-in reply [:structuredContent :truncated]) => true)
      (fact (<= (count (json/generate-string reply)) 10200) => true))))

(facts "help topic pagination is stable and unknown docs are explicit"
  (with-redefs [help/get-docs
                (constantly {"c" #{"C"} "a" #{"A"} "b" #{"B"}})]
    (let [result (run-calls config [["help" {"limit" 2}]])
          page (get-in result [:replies 0 :structuredContent])]
      (fact (:topics page) => ["a" "b"])
      (fact (:next_after page) => "b")
      (fact (get-in (run-calls config [["help" {"after" "b"}]])
                    [:replies 0 :structuredContent :topics]) => ["c"])
      (fact (get-in (run-calls config [["help" {"topic" "missing"}]])
                    [:replies 0 :structuredContent :found]) => false))))

(facts "PostgreSQL enforces both read-only execution and query cancellation"
  (with-history
    (fn []
      (let [url (:url (db/config))
            table (db/qualified-table-name "history")
            view (str table "_view")
            writer (str table "_writer")]
        (try
          (jdbc/db-do-commands
            url
            [(str "CREATE FUNCTION " writer "() RETURNS boolean AS $$ "
                  "BEGIN INSERT INTO " table "(body) VALUES ('write-marker'); "
                  "RETURN true; END $$ LANGUAGE plpgsql")
             (str "CREATE VIEW " view " AS SELECT * FROM " table
                  " WHERE " writer "()")])
          (with-redefs [db/qualified-table-name (constantly view)]
            (fact (get-in (run-calls config [["history_count" {}]])
                          [:replies 0 :isError]) => true))
          (fact (:count (first (jdbc/query
                                url [(str "SELECT count(*) FROM " table
                                          " WHERE body = 'write-marker'")])))
                => 0)
          (jdbc/db-do-commands
            url [(str "DROP VIEW " view)
                 (str "CREATE VIEW " view " AS SELECT h.* FROM " table
                      " h CROSS JOIN pg_sleep(5)")])
          (with-redefs [db/qualified-table-name (constantly view)]
            (fact (get-in (run-calls config [["history_count" {}]])
                          [:replies 0])
                  => (contains
                       {:isError true
                        :structuredContent
                        {:error "History query failed or timed out."}})))
          (finally
            (jdbc/db-do-commands
              url [(str "DROP VIEW IF EXISTS " view)
                   (str "DROP FUNCTION IF EXISTS " writer "()")])))))))

(fact "real ai and grok commands use native tools over Responses HTTP"
  (with-history
    (fn []
      (let [requests (atom [])]
        (support/with-http-server
          (fn [{:keys [body]}]
            (swap! requests conj body)
            {:body
             {:id "native-response" :status "completed"
              :output
              (if (and (seq (:tools body))
                       (not= "function_call_output"
                             (:type (last (:input body)))))
                [{:type "function_call" :id "output-item" :call_id "read-1"
                  :name "yetibot_history_count"
                  :arguments "{\"query\":\"launch\"}"}]
                [{:type "message" :role "assistant"
                  :content [{:type "output_text"
                             :text "25 launch messages."}]}])}})
          (fn [url]
            (let [p {:name "grok" :url (str url "/v1") :api "responses"
                     :model "grok-4.7" :native-tools ["web_search" "x_search"]}
                  read-config settings/get-config]
              (with-redefs [settings/get-config
                            (fn [spec path]
                              (if (= [:ai] path) {:value {:providers [p]}}
                                (read-config spec path)))
                            mcp/config (constantly config)
                            context/messages (fn [& _] messages)]
                (doseq [settings [nil {:servers []} config implicit-config]
                        cmd ["ai --provider grok find launch"
                             "grok find launch"]]
                  (reset! requests [])
                  (let [result (with-redefs [mcp/config (constantly settings)]
                                 (execution/run-command execution/request cmd))
                        first-turn (first @requests)
                        reply (last (:input (last @requests)))]
                    (fact (string/includes? (:value result)
                                            "25 launch messages.") => true)
                    (fact (:model first-turn) => "grok-4.7")
                    (fact (take 2 (:tools first-turn))
                          => [{:type "web_search"} {:type "x_search"}])
                    (fact (:call_id reply) => "read-1")
                    (fact (get-in (json/parse-string (:output reply) true)
                                  [:structuredContent :count]) => 25)))
                (reset! requests [])
                (execution/run-command execution/request
                                       "grok --no-tools find launch")
                (fact (count @requests) => 1)
                (fact (empty? (:tools (first @requests))) => true)))))))))

(facts "Discord history uses the incoming stored channel, not another room"
  (with-history
    (fn []
      (doseq [[room body] [["D1" "incoming thread"]
                           ["parent" "not this channel"]]]
        (history/create {:chat-source-adapter ":discord-prod"
                         :chat-source-room room :body body}))
      (let [request (assoc execution/request :chat-source
                          {:adapter :discord :uuid :discord-prod :room "D1"
                           :raw-event {:author {:id "U1"} :channel-id "D1"
                                       :id "M1"}})
            settings (-> config
                         (assoc-in [:toolsets 0 :adapters] ["discord"])
                         (assoc-in [:toolsets 0 :conversations]
                                   [":discord-prod/parent"]))
            result (run-calls settings [["history" {}]] request)]
        (fact (mapv :body (get-in result
                                 [:replies 0 :structuredContent :messages]))
              => ["incoming thread"])))))

(facts "disabled history prevents reads and configured secrets stay redacted"
  (with-redefs [stored-history/history-enabled (constantly false)
                jdbc/query (fn [& _]
                             (throw (AssertionError. "Database accessed")))]
    (fact (get-in (run-calls config [["history" {}]]) [:replies 0])
          => (contains {:isError true
                        :structuredContent {:error "History is disabled."}})))
  (with-history
    (fn []
      (with-redefs [provider (assoc provider :key "launch")]
        (fact (string/includes?
                (json/generate-string
                  (:replies (run-calls config [["history" {}]])))
                "launch") => false))
      (dotimes [_ 20]
        (history/create {:chat-source-adapter (pr-str "workspace")
                         :chat-source-room "private"
                         :body (apply str (repeat 1000 "u"))}))
      (with-redefs [provider (assoc provider :key "u")]
        (fact (get-in (run-calls config [["history" {"limit" 20}]])
                      [:replies 0])
              => (contains
                   {:isError true
                    :structuredContent
                    {:error "Native result exceeded the output limit."
                     :truncated true}}))))))

(def media-config
  (assoc-in config [:toolsets 0 :native-tools] ["meme" "gi"]))

(fact "media defaults follow credentials, explicit lists and GitHub isolation"
  (execution/in-event execution/request "ai tools"
    (fn [request]
      (let [actor (policy/actor request)
            reads ["yetibot/help" "yetibot/history" "yetibot/history_count"]]
        (doseq [meme? [false true] image? [false true]]
          (with-redefs [imgflip/configured? meme?
                        xai/config (if image? {:key "fake-image-key"} {})
                        mcp/config (constantly nil)]
            (fact (tools/tool-names "yetibot" actor)
                  => (cond-> reads
                       meme? (conj "yetibot/meme")
                       image? (conj "yetibot/gi")))
            (with-redefs [mcp/config (constantly config)]
              (fact (tools/tool-names "yetibot" actor) => reads))))
        (let [t (-> toolset (dissoc :native-tools)
                    (assoc :kind "github-read" :repositories ["owner/repo"]
                           :servers [{:name "reader" :tools ["get_commit"]}]))
              cfg {:toolsets [t]
                   :servers [{:name "reader" :tools ["get_commit"]}]}]
          (with-redefs [imgflip/configured? true
                        xai/config {:key "fake-image-key"}
                        mcp/config (constantly cfg)]
            (fact (tools/tool-names "yetibot" actor) => reads)))))))

(fact "meme search is literal, bounded and returns usable template IDs"
  (with-redefs [imgflip/configured? true
                imgflip/search-via-scrape (constantly [])
                imgflip/memes
                (constantly {:data {:memes [{:id "181913649" :name "Drake"
                                            :url "https://i.imgflip.com/a.jpg"}
                                           {:id "2" :name "DRAKE copy"}
                                           {:id "3" :name "Cat"}]}})]
    (let [result (run-calls nil [["meme" {"action" "search" "query" "drake"
                                          "limit" 1}]])]
      (fact (get-in result [:replies 0 :structuredContent :templates])
            => [{:id "181913649" :name "Drake"
                 :url "https://i.imgflip.com/a.jpg"}]))
    (fact (get-in (run-calls nil
                   [["meme" {"action" "search" "query" "["}]])
                  [:replies 0 :structuredContent :templates]) => [])))

(fact "meme captions are data, not commands, and generated URLs reach the model"
  (let [calls (atom [])]
    (with-redefs [imgflip/configured? true
                  imgflip/generate-meme
                  (fn [& args]
                    (swap! calls conj args)
                    {:success true
                     :data {:url "//i.imgflip.com/generated.jpg"}})]
      (let [result (run-calls nil
                     [["meme" {"action" "generate"
                                "template_id" "181913649"
                                "top" "no | cmd" "bottom" "yes `echo nope`"}]])]
        (fact @calls => [["181913649" "no | cmd" "yes `echo nope`"]]
              (get-in result [:replies 0 :structuredContent :url])
              => "https://i.imgflip.com/generated.jpg")))))

(fact "gi reuses generation and image storage without exposing binary data"
  (let [calls (atom [])]
    (with-redefs [xai/config {:key "fake-image-key"}
                  xai/generate-image (fn [& args]
                                       (swap! calls conj args)
                                       (println "private-gi-debug")
                                       "binary-image-not-for-model")
                  images/store-image! (fn [_] "native-image")
                  gemini/yetibot-base-url (constantly "https://yetibot.test")]
      (let [captured (atom nil)
            output (with-out-str
                     (reset! captured
                             (run-calls nil
                               [["gi" {"prompt" "a yeti on a helicopter"}]])))
            result @captured]
        (fact output => ""
              (ffirst @calls) => "a yeti on a helicopter"
              (count @calls) => 1
              (get-in result [:replies 0 :structuredContent :url])
              => "https://yetibot.test/generated-images/native-image.png"
              (string/includes? (json/generate-string (:replies result))
                                "binary-image-not-for-model") => false)))))

(fact "invalid media batches fail before any caption or paid image request"
  (let [calls (atom [])]
    (with-redefs [imgflip/configured? true
                  xai/config {:key "fake-image-key"}
                  gi/grokimage-cmd (fn [& args] (swap! calls conj args))
                  imgflip/generate-meme (fn [& args] (swap! calls conj args))]
      (doseq [[op args]
              [["gi" {}] ["gi" {"prompt" ""}]
               ["gi" {"prompt" "ok" "count" 100}]
               ["gi" {"prompt" "ok" "chat_source" "elsewhere"}]
               ["gi" {"prompt" (apply str (repeat 2001 "x"))}]
               ["meme" {"action" "search" "query" ""}]
               ["meme" {"action" "search" "query" "cat" "limit" 21}]
               ["meme" {"action" "execute" "command" "rm -rf /"}]
               ["meme" {"action" "generate" "template_id" "https://evil.test"
                         "top" "x" "bottom" "y"}]
               ["meme" {"action" "generate" "template_id" "1"
                         "top" (apply str (repeat 1001 "x")) "bottom" "y"}]]]
        (fact (error-message #(run-calls nil
                               [["gi" {"prompt" "valid image"}] [op args]]))
              => "Invalid arguments for native Yetibot media tool."))
      (fact @calls => []))))

(fact "configured credentials cannot become media prompts or captions"
  (let [calls (atom [])]
    (with-redefs [imgflip/configured? true
                  imgflip/config {:password "private-imgflip-password"}
                  xai/config {:key "private-image-key"}
                  provider (assoc provider :key "private-model-key")
                  gi/grokimage-cmd (fn [& args] (swap! calls conj args))]
      (doseq [secret ["private-image-key" "private-imgflip-password"
                      "private-model-key"]]
        (fact (error-message #(run-calls nil
                               [["gi" {"prompt" (str "render " secret)}]]))
              => "Media arguments contain configured credentials."))
      (fact @calls => []))))

(fact "an uncertain image call is sanitized and halts the rest of the batch"
  (let [captions (atom 0)]
    (with-redefs [imgflip/configured? true
                  xai/config {:key "private-image-key"}
                  gi/grokimage-cmd
                  (fn [_] (throw (Exception. "private-image-key timeout")))
                  imgflip/generate-meme (fn [& _] (swap! captions inc))]
      (let [result (run-calls nil
                     [["gi" {"prompt" "a yeti"}]
                      ["meme" {"action" "generate" "template_id" "1"
                                 "top" "no" "bottom" "yes"}]])]
        (fact @captions => 0
              (get-in result [:replies 1 :skipped]) => true
              (get-in result [:replies 0 :isError]) => true
              (get-in result [:result :result/data :mcp :tool-calls]) => 1
              (string/includes? (json/generate-string result)
                                "private-image-key") => false
              (get-in result [:sent 1 :options :tool_choice]) => "none")))))

(fact "explicit unavailable media tools report setup errors without API calls"
  (with-redefs [imgflip/configured? false xai/config {}
                gi/grokimage-cmd (fn [& _]
                                  (throw (AssertionError. "Paid call")))]
    (fact (get-in (run-calls media-config [["gi" {"prompt" "a yeti"}]])
                  [:replies 0 :isError]) => true)))

(fact "JSON escaping cannot hide configured media credentials"
  (let [secret "private-\"image\\password"
        calls (atom 0)]
    (with-redefs [imgflip/configured? true
                  imgflip/config {:password secret}
                  xai/config {:key "fake-image-key"}
                  gi/grokimage-cmd (fn [_]
                                    (swap! calls inc)
                                    {:result/error "Unexpected generation"})
                  imgflip/memes
                  (constantly {:data {:memes [{:id "1"
                                              :name (str "meme " secret)}]}})]
      (fact (error-message #(run-calls nil
                             [["gi" {"prompt" (str "render " secret)}]]))
            => "Media arguments contain configured credentials."
            (get-in (run-calls nil [["meme" {"action" "search"
                                             "query" "meme"}]])
                    [:replies 0 :structuredContent :templates 0 :name])
            => "meme [redacted]"
            @calls => 0))))

(fact "meme transport uses HTTPS for configured credentials"
  (let [urls (atom [])]
    (with-redefs [imgflip/configured? true
                  imgflip/config {:username "test" :password "fake-password"}
                  http/get-json
                  (fn [url]
                    (swap! urls conj url)
                    {:success true :data {:url "https://i.imgflip.com/a.jpg"}})]
      (run-calls nil [["meme" {"action" "generate" "template_id" "1"
                               "top" "top" "bottom" "bottom"}]])
      (fact (count @urls) => 1
            (string/starts-with? (first @urls)
                                 "https://api.imgflip.com/caption_image?")
            => true))))

(fact "gi edits use attachments from the trusted incoming source"
  (let [calls (atom [])
        url "https://images.example/source.png"
        request (assoc-in execution/request
                          [:chat-source :raw-event :attachments]
                          [{:url url :content-type "image/png"}])]
    (with-redefs [xai/config {:key "fake-image-key"}
                  xai/generate-image (fn [_ _ urls]
                                       (swap! calls conj urls) "image-data")
                  images/store-image! (fn [_] "edited")
                  gemini/yetibot-base-url (constantly "https://yetibot.test")]
      (let [result (run-calls nil [["gi" {"prompt" "make this a yeti"}]]
                              request)]
        (fact @calls => [[url]]
              (get-in result [:replies 0 :structuredContent :url])
              => "https://yetibot.test/generated-images/edited.png")))))

(fact "media generation rechecks policy immediately before the side effect"
  (let [authorizations (atom 0)
        calls (atom 0)]
    (with-redefs [xai/config {:key "fake-image-key"}
                  policy/authorize! (fn [& _]
                                      (when (> (swap! authorizations inc) 1)
                                        (ai/fail "Policy changed.")))
                  gi/grokimage-cmd (fn [_] (swap! calls inc))]
      (fact (error-message #(run-calls media-config
                             [["gi" {"prompt" "a yeti"}]]))
            => "Policy changed."
            @calls => 0))))
