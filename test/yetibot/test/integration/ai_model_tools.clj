(ns yetibot.test.integration.ai-model-tools
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [taoensso.timbre :as log]
            [yetibot.api.ai :as ai]
            [yetibot.api.ai-diagnostics :as diagnostics]
            [yetibot.api.cloudflare :as cf]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.cf :as cli]
            [yetibot.core.commands.echo :as echo]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-model-tools :as models]
            [yetibot.models.ai-native-tools :as native]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-wise-tools :as wise]
            [yetibot.test.support.ai-execution :as execution]))

(fact "structured model output compiles redaction patterns once per traversal"
  (let [compile-pattern re-pattern
        compilations (atom 0)
        result (with-redefs [clojure.core/re-pattern
                             (fn [text]
                               (swap! compilations inc)
                               (compile-pattern text))]
                 (#'models/safe-text
                   (json/generate-string
                     {:a "fixture/secret"
                      :b ["fixture/secret" "public"]})
                   {:secrets ["fixture/secret"]}))]
    (json/parse-string result true)
    => {:a "[redacted]" :b ["[redacted]" "public"]}
    @compilations => 1))

(def parent {:name "parent" :url "https://parent.example/v1" :model "p"})
(def child {:name "child" :url "https://child.example/v1" :model "c"
            :key "child-token" :api "responses"
            :native-tools ["web_search" "x_search"]})
(def cloudflare
  {:name "cloudflare" :model "@cf/vendor/chat" :key "cf-token"
   :url (str "https://api.cloudflare.com/client/v4/accounts/"
             (string/join (repeat 32 "a")) "/ai/v1")})
(def settings {:providers [parent child cloudflare]
               :default-provider "child"})
(def toolset
  {:name "delegate" :kind "search" :servers []
   :users ["U1"] :adapters ["slack"] :native-tools ["ai" "cf" "wise"]
   :providers [{:name "parent" :models ["p"]}
               {:name "child" :models ["c"]}
               {:name "cloudflare" :models ["@cf/vendor/chat"
                                            "@cf/cloudflare/clef"
                                            "@cf/cloudflare/clairic"
                                            "@cf/vendor/image"
                                            "@cf/vendor/unknown"]}]})
(def config {:servers [] :toolset toolset})
(def ask {"action" "ask" "provider" "child" "prompt" "Only this text"})
(def catalog
  {:source :cloudflare-workers-ai
   :models [{:id "@cf/vendor/chat" :task {:name "Text Generation"}
             :interface :chat}
            {:id "@cf/cloudflare/clef" :interface :decision}
            {:id "@cf/cloudflare/clairic" :interface :extraction}
            {:id "@cf/vendor/image" :interface :model-specific}]})

(defn error [f]
  (try (f) nil (catch Exception e (.getMessage e))))

(defn in-request [f]
  (execution/in-event execution/request "ai ask another model"
    (fn [request]
      (with-redefs [ai/config (constantly settings)]
        (f request)))))

(facts "delegation schemas satisfy Grok's complete object-branch contract"
  (doseq [[operation definition] models/definitions]
    (let [schema (:inputSchema definition)]
      (doseq [shape (cons schema (:oneOf schema))]
        (fact (:type shape) => "object")
        (fact (:additionalProperties shape) => false)
        (fact (every? #(contains? (:properties shape) (keyword %))
                       (:required shape)) => true))
      (fact (mapv #(get-in % [:properties :action :enum]) (:oneOf schema))
            => (if (= operation "cf") [["ask"] ["models"]]
                 [["ask"] ["models"] ["providers" "defaults" "reset_default"]
            ["set_default"]])))))

(facts "native availability is passive and explicit lists stay authoritative"
  (let [reads (atom 0)]
    (with-redefs [ai/config (constantly settings)
                  ai/resolve-provider (fn [_] (swap! reads inc))
                  cf/catalog (fn [_] (swap! reads inc))]
      (models/available-tools) => ["ai" "cf"]
      (native/names {:toolset (assoc toolset :native-tools ["help"])})
      => ["help"]
      (with-redefs [wise/available-tools (constantly [])]
        (take-last 4 (native/names {})) => ["ai" "cf" "util" "aliases"])
      @reads => 0))
  (with-redefs [ai/config (fn [] (ai/fail "not configured"))]
    (models/available-tools) => []))

(facts "pure validation enforces shape, provider/model policy and no secrets"
  (in-request
    (fn [_]
      (let [effects (atom 0)]
        (with-redefs [ai/resolve-provider (fn [_] (swap! effects inc))
                      cf/catalog (fn [_] (swap! effects inc))]
          (models/arguments! config "ai" ask) => ask
          (doseq [args [(assoc ask "provider" "missing")
                        (assoc ask "model" "not-allowed")
                        (assoc ask "url" "https://attacker")
                        (assoc ask "headers" {})
                        (assoc ask "key_file" "/tmp/token")
                        (assoc ask "user" "U1")
                        (assoc ask "tools" [])
                        (assoc ask "max_output_tokens" 2049)
                        (assoc ask "max_output_tokens" "1")
                        (assoc ask "prompt" "")
                        (assoc ask "prompt" (string/join (repeat 12001 "x")))
                        {"action" "models" "model" "c"}
                        {"action" "providers" "provider" "child"}
                        {"action" "ask" "prompt" "ok" "after" "x"}]]
            (fact (boolean (error #(models/arguments! config "ai" args)))
                  => true))
          (boolean
            (error #(models/arguments! (assoc config :secrets ["quoted\"key"])
                                       "ai"
                                       (assoc ask "prompt" "quoted\"key"))))
          => true
          @effects => 0)))))

(fact "single-hop Responses child has a real wire cap and no tools/history"
  (in-request
    (fn [request]
      (let [wire (atom nil)]
        (with-redefs [http/post
                      (fn [url options]
                        (reset! wire [url (:form-params options)])
                        {:status 200
                         :body {:status "completed"
                                :output [{:type "message"
                                          :content [{:type "output_text"
                                                     :text "answer"}]}
                                         ;; Even hostile recursion is inert.
                                         {:type "function_call" :name "ai"
                                          :arguments "{}" :call_id "loop"}]}})
                      tools/complete (fn [& _]
                                       (throw (AssertionError. "recursion")))
                      context/messages (fn [& _]
                                         (throw (AssertionError. "history")))]
          (:structuredContent
            (models/call! config "ai" ask (:chat-source request)))
          => {:untrusted true :provider "child" :model "c" :text "answer"
              :truncated false}
          (first @wire) => "https://child.example/v1/responses"
          (:input (second @wire))
          => [{:role "user" :content "Only this text"}]
          (:max_output_tokens (second @wire)) => 2048
          (:tools (second @wire)) => []
          (contains? (second @wire) :tool_choice) => false
          (contains? (second @wire) ::diagnostics/stage) => false)))))

(facts "Cloudflare chat eligibility is checked before paid inference"
  (in-request
    (fn [request]
      (let [posts (atom []) gets (atom 0)]
        (with-redefs [cf/catalog (fn [_] (swap! gets inc) catalog)
                      http/post
                      (fn [_ options]
                        (swap! posts conj (:form-params options))
                        {:status 200
                         :body {:choices [{:message {:content "CF answer"}}]}})]
          (doseq [model ["@cf/cloudflare/clef" "@cf/cloudflare/clairic"
                         "@cf/vendor/image" "@cf/vendor/unknown"]]
            (let [result (models/call!
                           config "cf" {"action" "ask" "model" model
                                        "prompt" "question"}
                           (:chat-source request))]
              (fact (:isError result) => true)
              (fact (:unknownOutcome result) => false)))
          (count @posts) => 0
          (:text (:structuredContent
                   (models/call! config "cf"
                                 {"action" "ask" "prompt" "question"
                                  "max_output_tokens" 42}
                                 (:chat-source request)))) => "CF answer"
          (:max_tokens (first @posts)) => 42
          (:tools (first @posts)) => []
          @gets => 5)))))

(facts "trust mismatch is denied before credentials, catalog or network"
  (in-request
    (fn [request]
      (let [effects (atom 0)]
        (with-redefs [ai/resolve-provider (fn [_] (swap! effects inc))
                      cf/catalog (fn [_] (swap! effects inc))
                      http/post (fn [& _] (swap! effects inc))]
          (:isError (models/call! config "ai" ask
                                  (assoc (:chat-source request) :room "other")))
          => true
          (let [actor (policy/actor request)
                tool (first (native/tools
                              (assoc-in config [:toolset :native-tools] ["ai"])
                              actor parent "p"))]
            (binding [yetibot.util.ai-execution/*event*
                      (assoc-in request [:user :id] "attacker")]
              (boolean (error #((:host-call tool) ask))) => true))
          @effects => 0)))))

(facts "raw and JSON-escaped credentials are redacted before output truncation"
  (in-request
    (fn [request]
      (let [token "quoted\"key\\value"
            file (java.io.File/createTempFile "child-token" ".txt")]
        (try
          (spit file token)
          (with-redefs
            [ai/config (constantly
                         (assoc settings :providers
                                [(-> child (dissoc :key)
                                     (assoc :key-file
                                            (.getAbsolutePath file)))]))
             ai/complete
             (fn [& _]
               {:result/value
                (json/generate-string
                  {"answer" (str token (string/join (repeat 13000 "x")))
                   "authorization" "unknown-sensitive-value"})})]
            (let [result (:structuredContent
                           (models/call! config "ai" ask
                                         (:chat-source request)))
                  text (:text result)]
              (:truncated result) => true
              (count text) => 12000
              (string/includes? text token) => false
              (string/includes? text "quoted") => false
              (string/includes? text "unknown-sensitive-value") => false)
            (with-redefs
              [ai/complete
               (fn [& _]
                 {:result/value
                  (json/generate-string
                    {"authorization" "unconfigured-secret"
                     "answer" (json/generate-string token)})})]
              (let [text (get-in (models/call!
                                  config "ai" ask (:chat-source request))
                                 [:structuredContent :text])
                    decoded (json/parse-string text)]
                (get decoded "authorization") => "[redacted]"
                (string/includes? text "quoted") => false)))
          (finally (.delete file)))))))

(fact "mixed JSON escapes cannot carry credentials through child prose"
  (in-request
    (fn [request]
      (with-redefs [ai/complete
                    (fn [& _]
                      {:result/value "Prose: child\\u002dtoken tail"})]
        (get-in (models/call! config "ai" ask (:chat-source request))
                [:structuredContent :text])
        => "Prose: [redacted] tail"))))

(fact "escaped credential input is rejected before child inference"
  (in-request
    (fn [request]
      (let [calls (atom 0)]
        (with-redefs [ai/complete (fn [& _] (swap! calls inc)
                                   {:result/value "unexpected"})]
          (let [result (models/call!
                         config "ai"
                         (assoc ask "prompt" "Prose: child\\u002dtoken tail")
                         (:chat-source request))]
            (:isError result) => true
            (:unknownOutcome result) => false
            @calls => 0))))))

(facts "catalog pages expose labels but never secret provider configuration"
  (in-request
    (fn [request]
      (with-redefs [cf/catalog (constantly catalog)]
        (let [result (:structuredContent
                       (models/call! config "cf" {"action" "models" "limit" 2}
                                     (:chat-source request)))]
          (mapv :interface (:models result)) => ["extraction" "decision"]
          (:has_more result) => true
          (:next_after result) => "@cf/cloudflare/clef"
          (string/includes? (str result) "cf-token") => false
          (let [next (:structuredContent
                       (models/call! config "cf"
                                     {"action" "models" "limit" 2
                                      "after" (:next_after result)}
                                     (:chat-source request)))]
            (mapv :id (:models next)) => ["@cf/vendor/chat" "@cf/vendor/image"]
            (:has_more next) => false))))))

(fact "resolved parent keys enter the existing run-tools redaction secrets"
  (in-request
    (fn [request]
      (let [file (java.io.File/createTempFile "parent-token" ".txt")
            token "parent\"key\\token"
            observed (atom nil)]
        (try
          (spit file token)
          (with-redefs
            [mcp/config
             (constantly {:servers []
                          :toolsets [(assoc toolset :native-tools ["help"])]})
             ai/chat
             (fn [provider _ _ _]
               (reset! observed (:key provider))
               {:choices
                [{:message
                  {:content (str "raw=" token ";encoded="
                                 (json/generate-string token))}}]})]
            (let [result (tools/complete
                           (assoc parent :key-file (.getAbsolutePath file))
                           "p" [] {:actor (policy/actor request)})]
              @observed => token
              (:result/value result)
              => "raw=[redacted];encoded=\"[redacted]\""
              (string/includes? (str (:result/data result)) "parent")
              => false))
          (finally (.delete file)))))))

(defn run-batch [calls fail?]
  (in-request
    (fn [request]
      (let [parent-turns (atom []) effects (atom [])
            chat ai/chat]
        (with-redefs
          [mcp/config (constantly {:servers [] :toolsets [toolset]
                                   :max-tool-calls 3})
           wise/available-tools (constantly ["wise"])
           wise/arguments! (fn [_ _ args] args)
           wise/call! (fn [& _] (swap! effects conj :wise)
                        {:structuredContent {:ok true}})
           cf/catalog (fn [_] (swap! effects conj :catalog) catalog)
           http/post (fn [& _]
                       (swap! effects conj :paid)
                       (if fail? (throw (Exception. "private HTTP body"))
                         {:status 200 :body {:status "completed"
                                            :output [{:type "message"
                                                      :content
                                                      [{:type "output_text"
                                                        :text "answer"}]}]}}))
           ai/chat
           (fn [p model messages options]
             (if (not= "parent" (:name p)) (chat p model messages options)
               (do
                 (swap! parent-turns conj {:messages messages :options options})
                 {:choices
                  [{:message
                    (if (= 1 (count @parent-turns))
                      {:tool_calls
                       (mapv (fn [i [name args]]
                               {:id (str i) :type "function"
                                :function {:name (str "yetibot_" name)
                                           :arguments
                                           (json/generate-string args)}})
                             (range) calls)}
                      {:content "Done"})}]})))]
          (let [result (try
                         (tools/complete parent "p" []
                                         {:actor (policy/actor request)})
                         (catch Exception _ :invalid))]
            {:result result :effects @effects :turns @parent-turns}))))))

(facts "one child counts as one existing host call; no extra agent loop"
  (let [result (run-batch [["ai" ask]] false)]
    (:effects result) => [:paid]
    (get-in result [:result :result/data :mcp :tool-calls]) => 1
    (count (:turns result)) => 2
    (mapv #(get-in % [:options ::diagnostics/stage]) (:turns result))
    => [:initial :follow-up]))

(facts "statically invalid mixed batches have zero effects"
  (doseq [invalid [(assoc ask "provider" "absent")
                   (assoc ask "model" "forbidden")
                   (assoc ask "max_output_tokens" 9999)
                   (assoc ask "key-file" "/tmp/no")]]
    (let [result (run-batch [["wise" {"action" "balance"}]
                             ["ai" invalid]] false)]
      (fact (:result result) => :invalid)
      (fact (:effects result) => []))))

(facts "unknown paid child outcome halts later Wise and CF calls, no retries"
  (let [result (run-batch [["ai" ask] ["wise" {"action" "balance"}]
                           ["cf" {"action" "ask" "prompt" "later"}]] true)]
    (:effects result) => [:paid]
    (get-in result [:result :result/data :mcp :tool-calls]) => 1
    (get-in result [:turns 1 :options :tool_choice]) => "none"
    (count (filter #(and (= "tool" (:role %))
                        (string/starts-with? (:content %)
                                            "This tool was not run"))
                   (get-in result [:turns 1 :messages]))) => 2))

(facts "cf CLI uses the real parser, fixed provider and text-only adapter"
  (in-request
    (fn [request]
      (let [calls (atom [])]
        (with-redefs
          [mcp/config (constantly {:servers [] :toolsets [toolset]})
           cf/catalog (constantly catalog)
           ai/complete
           (fn [p model messages options]
             (swap! calls conj [p model messages options])
             {:result/value "CLI answer"})
           context/messages (fn [& _] (throw (AssertionError. "history")))]
          (:result/value (cli/prompt-cmd
                           (assoc request :match
                                  "--model @cf/vendor/chat supplied prompt")))
          => "CLI answer"
          (get-in @calls [0 1]) => "@cf/vendor/chat"
          (get-in @calls [0 2])
          => [{:role "user" :content "supplied prompt"}]
          (get-in @calls [0 3 :tools]) => []
          (get-in (cli/prompt-cmd
                    (assoc request :match "--provider child forbidden"))
                  [:result/data :isError]) => true
          (mapv :interface (:models (:result/data (cli/models-cmd request))))
          => [:chat :decision :extraction :model-specific])))))

(fact "text-only CF rejects host tool budgets before any preparation"
  (let [effects (atom [])]
    (with-redefs [mcp/config (fn [] (swap! effects conj :config))
                  ai/config (fn [] (swap! effects conj :provider))
                  ai/resolve-provider
                  (fn [& _] (swap! effects conj :credential))
                  cf/catalog (fn [& _] (swap! effects conj :catalog))
                  ai/complete (fn [& _] (swap! effects conj :inference))]
      (get-in (cli/prompt-cmd {:match "--max-tool-calls 12 PRIVATE"})
              [:result/data :isError]) => true
      @effects => [])))

(fact "cf command is registered in the real production command handler"
  (let [seen (atom [])]
    (with-redefs [ai/config (constantly settings)
                  mcp/config (constantly {:servers [] :toolsets [toolset]})
                  cf/catalog (constantly catalog)
                  ai/complete
                  (fn [_ model messages options]
                    (swap! seen conj [model messages options])
                    {:result/value "Real handler answer"})
                  context/messages
                  (fn [& _] (throw (AssertionError. "history")))]
      (:value (execution/run-command
                execution/request
                "cf --model @cf/vendor/chat only supplied text"))
      => "Real handler answer"
      (count @seen) => 1
      (get-in @seen [0 1])
      => [{:role "user" :content "only supplied text"}]
      (get-in @seen [0 2 :max-output-tokens]) => 2048)))

(defn with-cf-private-fixture [f]
  (let [file (java.io.File/createTempFile "cf-private-token" ".txt")
        token "cf-sensitive-fixture-token"
        posts (atom 0)
        p (-> cloudflare (dissoc :key)
              (assoc :key-file (.getAbsolutePath file)))]
    (try
      (spit file token)
      (with-redefs
        [ai/config (constantly (assoc settings :providers [parent child p]))
         mcp/config (constantly {:servers [] :toolsets [toolset]})
         cf/catalog (constantly catalog)
         http/post
         (fn [& _]
           (swap! posts inc)
           {:status 200 :body {:choices [{:message {:content "answer"}}]}})]
        (f {:file file :token token :posts posts}))
      (finally (.delete file)))))

(defn private-error! [result forbidden unknown?]
  ;; Inspect interpreted output/data, not just the direct CF handler result.
  (fact (string? (:value result)) => true)
  (fact (contains? result :error) => false)
  (fact (get-in result [:data :isError]) => true)
  (fact (get-in result [:data :unknownOutcome]) => unknown?)
  (doseq [text forbidden]
    (fact (string/includes? (pr-str (select-keys result [:value :error :data]))
                            text) => false)))

(facts "CF human rejections never decorate errors with the evaluated prompt"
  (with-cf-private-fixture
    (fn [{:keys [file token posts]}]
      (let [path (.getAbsolutePath file)
            private "PRIVATE-CF-CONTEXT"]
        (doseq [command [(str "cf " private " " token)
                         (str "cf " private " " path)
                         (str "cf --invalid " private " " token)
                         (str "cf --model " private " " token)]]
          (private-error! (execution/run-command execution/request command)
                          [token path private] false))
        (with-redefs [ai/config
                      (fn [] (throw (ex-info (str token " " path) {})))]
          (private-error!
            (execution/run-command execution/request
                                   (str "cf " private " " token))
            [token path private] false)
          (private-error! (execution/run-command execution/request "cf models")
                          [token path] false))
        (with-redefs [mcp/config (constantly {:toolsets [] :servers []})]
          (private-error!
            (execution/run-command execution/request
                                   (str "cf " private " " token))
            [token path private] false))
        @posts => 0))))

(fact "CF does not echo secrets supplied only by an earlier pipeline stage"
  (with-cf-private-fixture
    (fn [{:keys [token posts]}]
      (let [echoes (atom 0)]
        (with-redefs [echo/echo-cmd
                      (fn [_]
                        (swap! echoes inc)
                        {:result/value token})]
          (private-error!
            (execution/run-command execution/request
                                   "echo private-fixture | cf | echo later")
            [token "private-fixture"] false)
          @echoes => 1
          @posts => 0)))))

(fact "uncertain paid CF CLI outcomes retain safe data and stop the pipeline"
  (with-cf-private-fixture
    (fn [{:keys [posts]}]
      (let [echoes (atom 0)]
        (with-redefs
          [http/post (fn [& _]
                       (swap! posts inc)
                       (throw (Exception. "PRIVATE-POST-ERROR")))
           echo/echo-cmd (fn [_]
                           (swap! echoes inc)
                           {:result/value "must not run"})]
          (private-error!
            (execution/run-command
              execution/request
              "cf PRIVATE-PAID-PROMPT | echo later | echo again")
            ["PRIVATE-PAID-PROMPT" "PRIVATE-POST-ERROR"] true)
          @posts => 1
          @echoes => 0)))))

(fact "safe diagnostics survive private child failure without retrying"
  (let [logs (atom []) posts (atom []) effects (atom [])
        secret "PRIVATE-DIAGNOSTIC account balance USD 123456.78"
        calls [["ai" ask] ["wise" {"action" "balance"}]
               ["cf" {"action" "ask" "prompt" secret}]]]
    (log/with-config
      (assoc log/*config* :level :trace :middleware []
             :appenders
             {:capture {:enabled? true :async? false
                        :fn #(swap! logs conj (:vargs %))}})
      (in-request
        (fn [request]
          (with-redefs
            [mcp/config (constantly {:servers [] :toolsets [toolset]
                                     :max-tool-calls 3})
             wise/available-tools (constantly ["wise"])
             wise/arguments! (fn [_ _ args] args)
             wise/call! (fn [& _] (swap! effects conj :wise))
             cf/catalog (fn [_] (swap! effects conj :catalog) catalog)
             http/post
             (fn [url options]
               (swap! posts conj [url options])
               (if (= 1 (count @posts))
                 {:status 200
                  :body
                  {:choices
                   [{:message
                     {:tool_calls
                      (mapv
                        (fn [i [name args]]
                          {:id (str i) :type "function"
                           :function
                           {:name (str "yetibot_" name)
                            :arguments (json/generate-string args)}})
                        (range) calls)}}]}}
                 {:status 400
                  :body {:error {:message secret
                                 :code "invalid_function_parameters"
                                 :param "tools"}}}))]
            (let [error (try
                          (tools/complete parent "p" []
                                          {:actor (policy/actor request)})
                          (catch Exception e e))
                  records (mapv second
                                (filter #(= "ai-provider-diagnostic"
                                            (first %)) @logs))]
              (get-in (ex-data error) [:ai/diagnostic :stage]) => :follow-up
              (mapv :stage records) => [:delegation :follow-up]
              (mapv :tool-choice records) => [:omitted :none]
              (mapv :error-code records)
              => [:invalid-tool-schema :invalid-tool-schema]
              @effects => []
              (count @posts) => 3
              (get-in @posts [1 1 :form-params :tools]) => []
              (get-in @posts [1 1 :form-params :max_output_tokens]) => 2048
              (get-in @posts [2 1 :form-params :tool_choice]) => "none"
              (every? #(not (contains? (second %) ::diagnostics/stage))
                      @posts) => true
              (string/includes? (pr-str [@logs (ex-data error)]) secret)
              => false
              (some #(re-find #"child-token|Only this text|123456|balance"
                              (pr-str %)) @logs) => nil)))))))

(fact "initial parent failure retains actual stage, count and choice"
  (in-request
    (fn [request]
      (with-redefs
        [mcp/config (constantly {:servers [] :toolsets [toolset]})
         wise/available-tools (constantly [])
         http/post (fn [& _] {:status 400 :body {}})]
        (let [error (try (tools/complete parent "p" []
                                         {:actor (policy/actor request)})
                         (catch Exception e e))
              record (:ai/diagnostic (ex-data error))]
          (:stage record) => :initial
          (:tool-choice record) => :auto
          (:tool-count record) => 2
          ;; Host instructions plus one current, parent-only budget message.
          (:item-count record) => 2
          (get-in (ex-data error) [:ai/tool-failure :reason])
          => :provider-failure
          (get-in (ex-data error) [:ai/tool-failure :budget])
          => {:limit 6 :used 0 :remaining 6 :requested 0}
          (get-in (ex-data error) [:ai/tool-failure :calls]) => [])))))
