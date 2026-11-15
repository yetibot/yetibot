(ns yetibot.test.integration.ai-autodraft
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.cloudflare :as cloudflare]
            [yetibot.api.ai :as api] [yetibot.api.clef :as clef] [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai] [yetibot.models.ai-context :as context]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.models.ai-github :as github]
            [yetibot.test.integration.ai-tools :as fixture]
            [yetibot.test.support.ai-execution :as chat]
            [yetibot.test.support.mcp :as support]
            [yetibot.test.integration.ai-command-charts :as caller]
            [yetibot.util.ai-execution :as execution]
            [yetibot.core.util.agent-execution :as origin]
            [yetibot.core.adapters.adapter :as adapters]
            [yetibot.core.models.users :as users]
            [yetibot.core.chat :as core-chat]
            [clj-slack.conversations :as slack]
            [yetibot.models.ai-tools :as tools]))

(defn run-command [request text]
  (caller/with-current-caller #(chat/run-command request text)))

(defn classification [decision]
  {:status 200 :body {:success true :errors []
    :result {:model "clef" :answers
      {:intent {:type "choice" :choice (name decision) :confidence 1
                :probabilities {:code_change (if (= decision :code_change) 1 0)
                                :answer (if (= decision :answer) 1 0)
                                :clarify (if (= decision :clarify) 1 0)}}
       :explicit_change {:type "noul" :noul 1}}}}})

(fact "automatic routing uses real model/MCP transports and opens exactly one draft"
  (let [requests (atom []) pushed (atom false)
        physical-post client/post execute-github github/execute!
        family-snapshots (atom []) history-reads (atom [])
        staging-attempts (atom 0) stage! proposals/stage!]
    (support/with-http-server
      (fixture/fixture-handler requests pushed)
      (fn [base]
        (let [provider {:name "local" :url (str base "/v1") :model "chat"}
              t {:name "github-pr" :kind "github-pr" :users ["U1"] :adapters ["slack"]
                 :conversations ["workspace/C1"] :repositories ["owner/repo"]
                 :providers [{:name "local" :models ["chat"]}
                             {:name "cloudflare" :models ["@cf/cloudflare/clef"]}] :base-branches ["main"]
                 :allowed-paths ["src/"] :write-server "writer"
                 :servers [{:name "reader" :tools fixture/reader-tools}]}
              config {:default-toolset "github-pr" :toolsets [t]
                      :servers [{:name "reader" :url (str base "/read") :tools fixture/reader-tools
                                 :github-api-token "ancestry-secret"}
                                {:name "writer" :url (str base "/write") :tools fixture/writer-tools
                                 :headers {"Authorization" "Bearer write-secret"}}]}
              request (assoc-in chat/request [:chat-source :raw-event :ts] (str (java.util.UUID/randomUUID)))]
          (with-redefs [api/config (constantly {:providers [provider]
                                               :intent-router {:enabled true :github-toolset "github-pr" :account-id (apply str (repeat 32 "a")) :key-file "/fixture/key"}})
                        github/execute! (fn [config proposal]
                                          (let [result (execute-github config proposal)]
                                            (swap! family-snapshots conj execution/*request*) result))
                        client/post (fn [url options]
                                      (swap! family-snapshots conj execution/*request*)
                                      (if (string/includes? url "/ai/run/@cf/cloudflare/clef")
                                        (classification :code_change)
                                        (physical-post url options)))
                        clef/credential! (constantly "fixture-clef-secret")
                        mcp/config (constantly config)
                        context/messages (fn [_ prompt]
                                           (swap! history-reads conj
                                             {:authorized? (boolean execution/*request*)
                                              :routing-pending? (boolean (:routing-pending? execution/*request*))})
                                           [{:role "user" :content "complete root history"}
                                            {:role "assistant" :content "complete prior answer"}
                                            {:role "user" :content prompt}])
                        client/get (fn [url _] {:status 200 :body {:sha (last (string/split url #"/"))
                                                                  :parents [{:sha fixture/base-sha}]}})]
            (let [result (run-command request "ai Fix src/a.clj in owner/repo")]
              (fact @history-reads => [{:authorized? true :routing-pending? false}]
                    (take-last 3 (:messages (:body (first (filter #(= "/v1/chat/completions" (:path %)) @requests)))))
                    => [{:role "user" :content "complete root history"}
                        {:role "assistant" :content "complete prior answer"}
                        {:role "user" :content "Fix src/a.clj in owner/repo"}]
                    (get-in result [:data :status]) => :succeeded
                    (get-in result [:data :pr-url]) => "https://github.com/owner/repo/pull/12"
                    (count (distinct (map :family-id @family-snapshots))) => 1
                    (:used @(:budget (first @family-snapshots))) => 17
                    (mapv :ordinal (:receipts @(:budget (first @family-snapshots)))) => (vec (range 1 18))
                    (mapv :parent-ordinal (take 3 (drop 3 (:receipts @(:budget (first @family-snapshots)))))) => [3 3 3]
                    (mapv :parent-ordinal (drop 7 (:receipts @(:budget (first @family-snapshots))))) => (vec (repeat 10 7))))
            (with-redefs [proposals/stage!
                          (fn [& args]
                            (swap! staging-attempts inc)
                            (apply stage! args))
                          context/messages (fn [_ prompt]
                                             [{:role "user" :content "fixture-clef-secret"}
                                              {:role "user" :content prompt}])
                          api/chat
                          (fn [_ _ messages options]
                            (fact (string/includes? (json/generate-string messages) "fixture-clef-secret") => false)
                            (let [name (some #(when (string/ends-with? (get-in % [:function :name]) "stage_changes")
                                               (get-in % [:function :name])) (:tools options))]
                              {:choices [{:message
                                          {:tool_calls [{:id "secret-call" :type "function"
                                                         :function {:name name :arguments
                                                                    (json/generate-string
                                                                      {"owner" "owner" "repo" "repo" "base" "main"
                                                                       "title" "Fix" "body" "Fix"
                                                                       "files" [{"path" "src/a.clj"
                                                                                 "content" "fixture-clef-secret"}]})}}]}}]}))]
              (let [r (assoc-in request [:chat-source :raw-event :ts]
                                (str (java.util.UUID/randomUUID)))
                    calls-before (count (filter
                                          #(= "tools/call"
                                              (get-in % [:body :method]))
                                          @requests))
                    writes-before (count (filter #(= "/write" (:path %))
                                                 @requests))
                    result (run-command
                             r "ai Fix src/a.clj in owner/repo")
                    trace (get-in result [:data :tool-trace])
                    row (first (:calls trace))]
                (fact (:value result) => string?
                      (string/includes? (:value result)
                                        "Tool budget: total=20 used=1") => true
                      (contains? result :error) => false
                      (get-in result [:data :isError]) => true
                      (:reason trace) => :tool-exception
                      (:budget trace) => {:limit 20 :used 1 :remaining 19
                                          :requested 1}
                      (count (:calls trace)) => 1
                      (:tool row) => "mcp_6___stage_changes"
                      (select-keys row [:call :outcome :attempted :confirmed])
                      => {:call 1 :outcome :unknown :attempted true
                          :confirmed false}
                      (:omitted trace) => 0
                      (:truncated trace) => false
                      ;; Core results include objects Cheshire cannot encode.
                      ;; Print the entire result, not only its safe fields.
                      (string/includes?
                        (binding [*print-length* nil *print-level* nil]
                          (pr-str result))
                        "fixture-clef-secret") => false
                      @staging-attempts => 0
                      (count (filter #(= "tools/call"
                                         (get-in % [:body :method]))
                                     @requests)) => calls-before
                      (count (filter #(= "/write" (:path %)) @requests))
                      => writes-before)))
            (run-command request "ai Fix src/a.clj in owner/repo")
            (fact (count (filter #(= "push_files" (get-in % [:body :params :name])) @requests)) => 1
                  (get-in (last (filter #(= "create_pull_request" (get-in % [:body :params :name])) @requests))
                          [:body :params :arguments :draft]) => true
                  (string/includes?
                    (get-in (first (filter #(= "create_branch" (get-in % [:body :params :name])) @requests))
                            [:body :params :arguments :branch]) "request-") => true)))))))

(fact "automatic route retains its explicit physical allowance and stops before an unaffordable PR"
  (let [requests (atom []) pushed (atom false)]
    (support/with-http-server
      (fixture/fixture-handler requests pushed)
      (fn [base]
        (let [provider {:name "local" :url (str base "/v1") :model "chat"}
              t {:name "github-pr" :kind "github-pr" :users ["U1"]
                 :adapters ["slack"] :conversations ["workspace/C1"]
                 :repositories ["owner/repo"] :base-branches ["main"]
                 :providers [{:name "local" :models ["chat"]}
                             {:name "cloudflare" :models ["@cf/cloudflare/clef"]}]
                 :allowed-paths ["src/"] :write-server "writer"
                 :servers [{:name "reader" :tools fixture/reader-tools}]}
              config {:default-toolset "github-pr" :toolsets [t]
                      :servers
                      [{:name "reader" :url (str base "/read")
                        :tools fixture/reader-tools
                        :github-api-token "ancestry-secret"}
                       {:name "writer" :url (str base "/write")
                        :tools fixture/writer-tools}]}
              request (assoc-in chat/request [:chat-source :raw-event :ts]
                                (str (java.util.UUID/randomUUID)))]
          (with-redefs [api/config
                        (constantly {:providers [provider]
                                     :intent-router
                                     {:enabled true
                                      :github-toolset "github-pr" :account-id (apply str (repeat 32 "a")) :key-file "/fixture/key"}})
                        clef/classify! (constantly :code-change)
                        clef/credential! (constantly "fixture-clef-secret")
                        mcp/config (constantly config)
                        context/messages
                        (fn [_ prompt] [{:role "user" :content prompt}])
                        client/get
                        (fn [url _]
                          {:status 200
                           :body {:sha (last (string/split url #"/"))
                                  :parents [{:sha fixture/base-sha}]}})]
            (let [result (run-command
                           request
                           (str "ai --max-tool-calls 12 "
                                "Fix src/a.clj in owner/repo"))
                  model-requests (filter #(= "/v1/chat/completions"
                                             (:path %)) @requests)
                  messages (get-in (first model-requests) [:body :messages])]
              (fact (get-in result [:data :status]) => :failed
                    (:max-tool-calls config) => nil
                    (:max-tool-calls t) => nil
                    (count model-requests) => 1
                    (some #(= (str "Host admission budget: total=12 admitted=0 reserved=0 remaining=12; tool calls=0. Keep the entire "
                                   "next batch within remaining. When "
                                   "exhausted, stop fetching and answer "
                                   "with available evidence.")
                              (:content %)) messages) => true
                    (count (filter #(= "push_files"
                                       (get-in % [:body :params :name]))
                                   @requests)) => 1
                    (count (filter #(= "create_pull_request"
                                       (get-in % [:body :params :name]))
                                   @requests)) => 0))))))))


(defn route-wire [decision budget allow-classifier?]
  (let [sent (atom []) request (assoc-in chat/request [:chat-source :raw-event :ts] (str (java.util.UUID/randomUUID)))
        provider {:name "local" :url "https://synthetic.invalid/v1" :model "chat"}
        providers (cond-> [{:name "local" :models ["chat"]}]
                    allow-classifier? (conj {:name "cloudflare" :models ["@cf/cloudflare/clef"]}))
        github {:name "github-pr" :kind "github-pr" :users ["U1"] :adapters ["slack"]
                :providers providers :repositories ["owner/repo"] :base-branches ["main"]
                :allowed-paths ["src/"] :write-server "writer"
                :servers [{:name "reader" :tools fixture/reader-tools}]}
        config {:default-toolset "answer" :toolsets
                [{:name "answer" :kind "search" :servers [] :native-tools []
                  :users ["U1"] :adapters ["slack"] :providers providers} github]
                :servers [{:name "reader" :tools fixture/reader-tools :github-api-token "fixture-secret"}
                          {:name "writer" :tools fixture/writer-tools}]}
        router {:enabled true :github-toolset "github-pr" :account-id (apply str (repeat 32 "a")) :key-file "/fixture/key"}
        families (atom [])
        route-source execution/with-routing-source*
        captured-freeze (atom nil)]
    (with-redefs [api/config (constantly {:providers [provider] :intent-router router})
                  mcp/config (constantly config)
                  execution/with-routing-source*
                  (fn [prepared args f]
                    (route-source prepared args
                      (fn [freeze!]
                        (reset! captured-freeze freeze!)
                        (let [result (f freeze!)]
                          (fact (try (freeze! :code-change (fn [] :forbidden)) false
                                     (catch Exception _ true)) => true)
                          result))))
                  clef/credential! (constantly "fixture-secret")
                  context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                  client/post (fn [url options]
                                (swap! families conj execution/*request*)
                                (swap! sent conj {:url url :ordinal (:used @(:budget execution/*request*))
                                                  :cap (get-in options [:form-params :max_completion_tokens])})
                                (if (string/includes? url "/ai/run/")
                                  (do
                                    (fact (:routing-pending? execution/*request*) => true
                                          @(:tool-ceiling execution/*request*) => #{})
                                    (fact (try (execution/admit-operation! :command) false (catch Exception _ true)) => true)
                                    (fact (try (tools/complete provider "chat" [] {}) false (catch Exception _ true)) => true)
                                    (classification decision))
                                  {:status 200 :body {:choices [{:message {:role "assistant" :content "bounded answer"}}]}}))]
      (let [text (str "ai --max-tool-calls " budget " --max-output-tokens 91 Explain owner/repo")
            result (run-command request text)
            before (count @sent)
            repeated (run-command request text)]
        (fact (count @sent) => before)
        (when-let [freeze! @captured-freeze]
          (fact (try (freeze! :answer (fn [] :late-effect)) false (catch Exception _ true)) => true))
        {:result result :sent @sent :families @families :repeated repeated}))))

(fact "ai-autodraft answer keeps the classifier's finite family, real ordinal and prepared cap"
  (let [{:keys [result sent families]} (route-wire :answer 20 true)]
    (:value result) => "bounded answer"
    (mapv :ordinal sent) => [1 2]
    (mapv :cap sent) => [nil 91]
    (count (distinct (map :family-id families))) => 1
    (:closed? @(:budget (first families))) => true
    (mapv :state (:receipts @(:budget (first families)))) => [:definitive :definitive]))

(fact "ai-autodraft clarify, exhausted answer and forbidden classifier never acquire tool rights or reset budgets"
  (count (:sent (route-wire :clarify 20 true))) => 1
  (count (:sent (route-wire :answer 1 true))) => 1
  (count (:sent (route-wire :answer 20 false))) => 0)

(fact "frozen automatic changes reauthorize after classification before credentials and Slack history"
  (doseq [revoked [:adapter :user]]
    (let [credentials (atom 0) history (atom 0) classifier (atom 0) subsequent (atom 0)
          key-file (java.io.File/createTempFile "autodraft-router-" ".key")
          read-file slurp
          classify clef/classify!
          provider {:name "local" :url "https://automatic.invalid/v1" :model "chat"}
          t {:name "github-pr" :kind "github-pr" :users ["U1"] :adapters ["slack"]
             :repositories ["owner/repo"] :base-branches ["main"] :allowed-paths ["src/"]
             :servers [{:name "reader" :tools ["get_file_contents" "get_commit"]}] :write-server "writer"
             :providers [{:name "local" :models ["chat"]}
                         {:name "cloudflare" :models ["@cf/cloudflare/clef"]}]}
          config {:default-toolset "github-pr" :toolsets [t]
                  :servers [{:name "reader" :url "https://automatic.invalid/read"
                             :github-api-token "read-fixture-secret" :tools ["get_file_contents" "get_commit"]}
                            {:name "writer" :url "https://automatic.invalid/write"}]}
          request (assoc-in chat/request [:chat-source :raw-event]
                            {:user "U1" :channel "C1" :ts (str (System/nanoTime) ".0") :thread_ts "1.0"})]
      (try
        (spit key-file "classification-key")
        (with-redefs [api/config (constantly {:providers [provider]
                                             :intent-router {:enabled true :github-toolset "github-pr"
                                                             :account-id (apply str (repeat 32 "a"))
                                                             :key-file (.getAbsolutePath key-file)}})
                      mcp/config (constantly config)
                      clojure.core/slurp (fn [& args]
                                           (when (= (.getAbsolutePath key-file) (str (first args)))
                                             (swap! credentials inc))
                                           (apply read-file args))
                      slack/replies (fn [& _] (swap! history inc) {:ok true :messages []})
                      mcp/connect! (fn [& _] (swap! subsequent inc) (throw (Exception. "Unexpected discovery")))
                      clef/classify! (fn [& args]
                                       (let [decision (apply classify args)]
                                         (fact decision => :code-change)
                                         (case revoked
                                           :adapter (swap! adapters/adapters dissoc "workspace")
                                           :user (swap! users/users dissoc {:adapter :slack :id "U1"}))
                                         decision))
                      client/post (fn [url _]
                                    (if (string/includes? url "/ai/run/@cf/cloudflare/clef")
                                      (do (swap! classifier inc)
                                          (classification :code_change))
                                      (do (swap! subsequent inc) {:status 500 :body {}})))
                      client/get (fn [& _] (swap! subsequent inc) {:status 500 :body {}})]
          (chat/with-registered-caller request
            #(binding [core-chat/*adapter* (assoc core-chat/*adapter* :config {:token "history-fixture"})]
               (chat/run-command request "ai Fix src/a.clj in owner/repo")))
          @classifier => 1
          @credentials => 1
          @history => 0
          @subsequent => 0)
        (finally (.delete key-file))))))


(fact "implicit Zen routing spends classifier and answer on the prepared source allocation"
  (doseq [budget [1 2]]
    (let [file (java.io.File/createTempFile "zen-router-" ".key")
          provider {:name "zen" :url "https://opencode.ai/inference/openai/v1" :key-file (.getAbsolutePath file)}
          providers [{:name "zen" :models ["zen-chat"]} {:name "cloudflare" :models ["@cf/cloudflare/clef"]}]
          t {:name "answer" :kind "search" :servers [] :native-tools [] :users ["U1"] :adapters ["slack"] :providers providers}
          github (assoc (dissoc t :native-tools) :name "github-pr" :kind "github-pr" :repositories ["owner/repo"] :base-branches ["main"]
                        :allowed-paths ["src/"] :write-server "writer" :servers [{:name "reader" :tools fixture/reader-tools}])
          config {:default-toolset "answer" :toolsets [t github]
                  :servers [{:name "reader" :tools fixture/reader-tools :github-api-token "fixture-secret"}
                            {:name "writer" :tools fixture/writer-tools}]}
          router {:enabled true :github-toolset "github-pr" :account-id (apply str (repeat 32 "a")) :key-file "/fixture/key"}
          stages (atom []) sent (atom []) read-bytes cloudflare/read-bytes
          snapshot #(hash-map :request execution/*request* :context origin/*context*)]
      (spit file "synthetic-zen-key")
      (try
        (with-redefs [api/config (constantly {:providers [provider] :intent-router router})
                      mcp/config (constantly config)
                      clef/credential! (constantly "fixture-secret")
                      cloudflare/read-bytes (fn [& args] (swap! stages conj (snapshot)) (apply read-bytes args))
                      context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                      client/get (fn [_ options]
                                   (swap! stages conj (snapshot))
                                   (let [body {:data [{:id "zen-chat" :type "chat"
                                     :modalities {:input ["text"] :output ["text"]} :api {:chat-completions {}}
                                     :cost {:text {:input {:price 0} :output {:price 0}}}}]}]
                                     {:status 200 :body (if (= :stream (:as options))
                                       (java.io.ByteArrayInputStream. (.getBytes (json/generate-string body) "UTF-8")) body)}))
                      client/post (fn [url options]
                                    (swap! sent conj (assoc (snapshot) :url url :form (:form-params options)
                                                           :ordinal (:used @(:budget execution/*request*))))
                                    (if (string/includes? url "/ai/run/") (classification :answer)
                                      {:status 200 :body {:choices [{:message {:role "assistant" :content "bounded Zen answer"}}]
                                                         :usage {:prompt_tokens 7 :completion_tokens 3 :cost 0.002M}}}))]
          (let [result (run-command (assoc-in chat/request [:chat-source :raw-event :ts] (str (java.util.UUID/randomUUID)))
                                   (str "ai --max-tool-calls " budget " --max-output-tokens 91 Explain owner/repo"))]
            (when (= budget 2) (get-in result [:data :ai/cost :usd]) => 0.002M))
          (count @sent) => budget
          (mapv :ordinal @sent) => (vec (range 1 (inc budget)))
          (count (distinct (map #(get-in % [:request :family-id]) (concat @stages @sent)))) => 1
          (count (distinct (map #(get-in % [:request :usage-context-id]) (concat @stages @sent)))) => 1
          (every? #(origin/capability? (:context %)) (concat @stages @sent)) => true
          (let [request (:request (first @sent))]
            (:used @(:budget request)) => budget
            (mapv :ordinal (:receipts @(:budget request))) => (vec (range 1 (inc budget))))
          (when (= budget 2)
            (get-in (last @sent) [:form :model]) => "zen-chat"
            (get-in (last @sent) [:form :max_tokens]) => 91))
        (finally (.delete file))))))


(defn direct-controls-denied? []
  (every? (fn [control]
            (try (execution/require-direct! {:args control}) false
                 (catch Exception _ true)))
          ["approve generated" "proposal generated" "default zen generated" "limits reset"]))

(defn prepared-zen-change [revoke]
  (let [requests (atom []) pushed (atom false) stages (atom []) claims (atom []) reviewed (atom []) remembered (atom [])
        post client/post claim proposals/claim-automatic! review proposals/review!
        stage proposals/stage! execute-github github/execute! remember discord/remember-command-continuation!
        file (java.io.File/createTempFile "zen-root-" ".key")]
    (spit file "synthetic-router-key")
    (try
      (support/with-http-server
        (fixture/fixture-handler requests pushed)
        (fn [base]
          (let [provider {:name "zen" :url "https://opencode.ai/inference/openai/v1"
                          :key-file (.getAbsolutePath file)}
                providers [{:name "zen" :models ["root-chat"]}
                           {:name "cloudflare" :models ["@cf/cloudflare/clef"]}]
                t {:name "github-pr" :kind "github-pr" :users ["U1"] :adapters ["slack"]
                   :repositories ["owner/repo"] :base-branches ["main"] :allowed-paths ["src/"]
                   :providers providers :write-server "writer"
                   :servers [{:name "reader" :tools fixture/reader-tools}]}
                config {:default-toolset "github-pr" :toolsets [t]
                        :servers [{:name "reader" :url (str base "/read") :tools fixture/reader-tools
                                   :github-api-token "ancestry-fixture-secret"}
                                  {:name "writer" :url (str base "/write") :tools fixture/writer-tools}]}
                policy-config (atom config)
                settings (atom {:providers [provider]
                                :intent-router {:enabled true :github-toolset "github-pr"
                                                :account-id (apply str (repeat 32 "a"))
                                                :key-file (.getAbsolutePath file)}})
                request (-> chat/request
                            (update-in [:chat-source :raw-event] dissoc :thread_ts)
                            (assoc-in [:chat-source :raw-event :ts] (str (System/nanoTime) ".0")))
                text "ai --max-tool-calls 20 --max-output-tokens 91 Fix src/a.clj in owner/repo"]
            (with-redefs [api/config #(deref settings) mcp/config #(deref policy-config)
                          discord/remember-command-continuation!
                          (fn [route] (swap! remembered conj route) (remember route))
                          proposals/stage!
                          (fn [& args]
                            (fact execution/*direct-input* => nil
                                  execution/*command-capture* => true
                                  (direct-controls-denied?) => true)
                            (apply stage args))
                          github/execute!
                          (fn [config p]
                            (fact execution/*direct-input* => nil
                                  execution/*command-capture* => true
                                  (direct-controls-denied?) => true)
                            (execute-github config p))
                          proposals/review!
                          (fn [& args]
                            (let [p (apply review args)]
                              (swap! reviewed conj p)
                              (case revoke
                                :provider (swap! settings assoc-in [:providers 0 :url] "https://changed.invalid/v1")
                                :router (swap! settings assoc-in [:intent-router :enabled] false)
                                :user (swap! users/users dissoc {:adapter :slack :id "U1"})
                                :role (swap! users/users assoc-in [{:adapter :slack :id "U1"} :yetibot?] true)
                                :repository (swap! policy-config assoc-in [:toolsets 0 :repositories] ["other/repo"])
                                :closed (execution/close-family! execution/*request*)
                                :event (set! execution/*event* (assoc execution/*event* :body "!ai forged"))
                                :family (set! execution/*request* (assoc execution/*request* :family-id "foreign-family"))
                                nil)
                              p))
                          proposals/claim-automatic!
                          (fn [& args]
                            (fact (direct-controls-denied?) => true)
                            (swap! claims conj {:input execution/*direct-input*
                                                :capture execution/*command-capture*
                                                :request execution/*request*})
                            (try
                              (let [p (apply claim args)]
                                (swap! claims conj {:proposal p}) p)
                              (catch Exception e
                                (swap! claims conj {:denial (.getMessage e)})
                                (throw e))))
                          client/get
                          (fn [url options]
                            (swap! stages conj execution/*request*)
                            (if (string/starts-with? url "https://opencode.ai/")
                              (let [body {:data [{:id "root-chat" :type "chat"
                                                  :modalities {:input ["text"] :output ["text"]}
                                                  :api {:chat-completions {}}
                                                  :cost {:text {:input {:price 0} :output {:price 0}}}}]}]
                                {:status 200 :body (if (= :stream (:as options))
                                                   (java.io.ByteArrayInputStream.
                                                     (.getBytes (json/generate-string body) "UTF-8")) body)})
                              {:status 200 :body {:sha (last (string/split url #"/"))
                                                :parents [{:sha fixture/base-sha}]}}))
                          client/post
                          (fn [url options]
                            (swap! stages conj execution/*request*)
                            (when (or (string/includes? url "/ai/run/")
                                      (string/starts-with? url "https://opencode.ai/"))
                              (fact execution/*direct-input* => nil
                                    execution/*command-capture* => true
                                    (try (execution/require-direct! {:args "limits reset"}) false
                                         (catch Exception _ true)) => true))
                            (cond
                              (string/includes? url "/ai/run/") (classification :code_change)
                              (string/starts-with? url "https://opencode.ai/")
                              (post (str base "/v1/chat/completions") options)
                              :else (post url options)))]
              (let [result (chat/run-registered-command request text)
                    first-stages @stages
                    repeated (chat/run-registered-command request text)]
                {:result result :repeated repeated :requests @requests :claims @claims
                 :stages first-stages :pushed @pushed :reviewed @reviewed :remembered @remembered})))))
      (finally (.delete file)))))

(fact "prepared implicit Zen root stages and really claims its sealed automatic draft once"
  (let [{:keys [result repeated requests claims stages remembered]} (prepared-zen-change nil)
        p (:proposal (last claims))
        family (first stages)]
    (get-in result [:data :status]) => :succeeded
    (:error result) => nil
    (count (filter #(contains? % :input) claims)) => 1
    (:value result) => "Draft PR: https://github.com/owner/repo/pull/12"
    (:value repeated) => (:value result)
    remembered => []
    (:execution p) => :automatic
    (:request-key p) => #"[a-f0-9]{64}"
    (:hash p) => string?
    (:actor p) => {:adapter "slack" :connection "workspace" :user "U1"
                  :conversation "workspace/C1" :thread "C1"}
    (:model p) => "root-chat"
    (get-in p [:provider :name]) => "zen"
    (get-in p [:toolset :name]) => "github-pr"
    (count (filter #(= "create_pull_request" (get-in % [:body :params :name])) requests)) => 1
    (get-in (first (filter #(= "create_pull_request" (get-in % [:body :params :name])) requests))
            [:body :params :arguments :draft]) => true
    (count (filter :input claims)) => 1
    (count (distinct (map :family-id stages))) => 1
    (:limit @(:budget family)) => 20
    (:generation-limit family) => 91
    (mapv :ordinal (:receipts @(:budget family))) => (vec (range 1 (inc (:used @(:budget family)))))
    (:closed? @(:budget family)) => true))

(fact "prepared Zen final claim still rejects current provider router and user revocation without retry"
  (doseq [revoked [:provider :router :user :role :repository :closed :event :family]]
    (let [{:keys [result requests claims pushed reviewed]} (prepared-zen-change revoked)]
      (get-in result [:data :status]) => nil
      (count reviewed) => 1
      (count (filter #(= "create_pull_request" (get-in % [:body :params :name])) requests)) => 0
      (count (filter :proposal claims)) => 0
      pushed => false)))
