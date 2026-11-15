(ns yetibot.test.integration.ai-responses
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.grok]
            [yetibot.commands.ai :as command]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.core.config :as config]
            [yetibot.core.util.xai :as xai]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-datasets :as datasets]
            [yetibot.core.hooks :as hooks]
            [yetibot.models.ai-context :as context]
            [yetibot.test.integration.ai-command-charts :as caller]
            [yetibot.core.util.public-result :as public]
            [yetibot.util.ai-execution :as host-execution]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.test.support.mcp :as support]))

(def messages [{:role "user" :content "Identify our account and cite X."}])
(def account-tool
  {:name "whoami" :description "Read account identity"
   :inputSchema {:type "object" :properties {}}})
(def agentic-state
  [{:type "reasoning" :id "reasoning-1"
    :encrypted_content "opaque-reasoning" :summary []}
   {:type "x_search_call" :id "native-search-1" :status "completed"
    :encrypted_content "opaque-native-tool-output"}])

(defn function-call [name call-id arguments]
  {:type "function_call" :id (str "item-" call-id) :call_id call-id
   :name name :arguments arguments :status "completed"})

(defn account-function-name [body]
  (some #(when (string/ends-with? (:name % "") "__whoami") (:name %))
        (:tools body)))

(defn response [output]
  {:id "response-id" :status "completed" :output output})

(defn text-output [text]
  {:type "message" :role "assistant"
   :content [{:type "output_text" :text text
              :annotations [{:type "url_citation"
                             :url "https://x.com/example/status/1"}]}]})

(defn last-turn [requests]
  (:body (last (filter #(= "/v1/responses" (:path %)) @requests))))

(defn with-runtime [turn mcp-result f]
  (let [requests (atom []) calls (atom [])]
    (support/with-http-server
      (fn [{:keys [path method body] :as request}]
        (swap! requests conj request)
        (if (= path "/v1/responses")
          ;; xAI rejects a selection directive with no advertised tools.
          ;; Validate before dispatching the fixture turn, unlike an always-200
          ;; mock that lets an impossible final follow-up appear successful.
          (if (and (empty? (:tools body))
                   (contains? body :tool_choice))
            {:status 400
             :body {:error
                    {:type "invalid_request_error"
                     :message (str "A tool_choice was set on the request "
                                   "but no tools were specified.")}}}
            (let [result (turn body)]
              (if (fn? result) {:stream result} {:body result})))
          (case method
            "GET" {:status 405}
            "DELETE" {:status 204}
            "POST"
            (if-not (:id body) {:status 202}
              {:headers {"Mcp-Session-Id" "fixture-session"}
               :body {:jsonrpc "2.0" :id (:id body)
                      :result
                      (case (:method body)
                        "tools/list" {:tools [account-tool]}
                        "tools/call" (do (swap! calls conj (:params body))
                                         mcp-result)
                        (support/rpc-result body))}}))))
      (fn [url]
        (let [provider {:name "grok" :url (str url "/v1")
                        :key "PROVIDER_SECRET" :api "responses"
                        :native-tools ["web_search" "x_search"]}
              config {:max-tool-calls 2
                      :servers [{:name "cloudflare-read"
                                 :url (str url "/mcp") :tools ["whoami"]
                                 :headers {"Authorization"
                                           "Bearer CLOUDFLARE_SECRET"}}]}]
          (with-redefs [mcp/config (constantly config)
                        ai/config (constantly {:providers [(assoc provider :model "grok-4.7")]})]
            (execution/in-registered-event execution/request "grok fixture"
              (fn [_] (f provider requests calls)))))))))

(defn write-event! [out event]
  (.write ^java.io.OutputStream out
          (.getBytes (str "data: " (json/generate-string event) "\n\n")
                     "UTF-8"))
  (.flush ^java.io.OutputStream out))

(fact "real SSE progress arrives before completion, never executing deltas"
  (let [release (promise) observed (promise) seen (atom [])
        turns (atom 0) original (atom nil)]
    (with-runtime
      (fn [body]
        (if (= 1 (swap! turns inc))
          (let [output (conj agentic-state
                             (assoc (text-output "I have a lead.")
                                    :id "lead" :status "completed")
                             (function-call (account-function-name body)
                                            "stream-call" "{}"))]
            (reset! original output)
            (fn [out]
              (write-event! out {:type "response.web_search_call.searching"})
              (write-event! out {:type "response.output_item.done"
                                 :output_index 2 :item (nth output 2)})
              (write-event! out {:type "response.function_call_arguments.delta"
                                 :delta "PRIVATE-ARGUMENTS"})
              (write-event! out {:type "response.output_item.added"
                                 :item (last output)})
              (deref release 5000 nil)
              (write-event! out {:type "response.completed"
                                 :response (response output)})))
          (fn [out]
            (write-event! out {:type "response.output_text.delta"
                               :delta "Verified streaming account answer."})
            (write-event! out {:type "response.completed"
                               :response
                               (response [(text-output "Yetibot.")])}))))
      {:structuredContent {:name "Yetibot"} :content []}
      (fn [provider requests calls]
        (let [result (future
                       (tools/complete
                         provider "grok-4.7" messages
                         {:on-progress
                          (fn [event]
                            (swap! seen conj event)
                            (when (:phase event)
                              (deliver observed true)))}))]
          (try
            (fact (deref observed 3000 :timeout) => true
                  @calls => [])
            (finally (deliver release true)))
          (let [value (deref result 5000 :timeout)
                requests (filter #(= "/v1/responses" (:path %)) @requests)
                next-body (:body (second requests))]
            (fact
              (:result/value value) => "Yetibot."
              (get-in value [:result/data :mcp :tool-calls]) => 1
              @calls => [{:name "whoami" :arguments {}}]
              (mapv #(get-in % [:body :stream]) requests) => [true true]
              (mapv #(get-in % [:body :store]) requests) => [false false]
              (take-last (count @original) (butlast (:input next-body)))
              => @original
              (:call_id (last (:input next-body))) => "stream-call"
              (boolean
                (some #(re-find #"^Running mcp_.*__whoami$" (:phase % ""))
                      @seen)) => true
              (some #(re-find #"PRIVATE|opaque" (pr-str %)) @seen) => nil
              (vec (keep :answer @seen))
              => [])))))))

(facts "completed SSE batches still validate every call before execution"
  (doseq [kind [:unknown :duplicate :over-budget]]
    (let [seen (atom [])]
      (with-runtime
        (fn [body]
          (let [call (function-call (account-function-name body) "one" "{}")
                output (case kind
                         :unknown [call (function-call "forbidden" "two" "{}")]
                         :duplicate [call call]
                         :over-budget [call
                                       (function-call (:name call) "two" "{}")
                                       (function-call (:name call) "three"
                                                      "{}")])]
            (fn [out]
              (write-event! out {:type "response.output_item.added"
                                 :item call})
              (write-event! out {:type "response.completed"
                                 :response (response output)}))))
        {:content []}
        (fn [provider _ calls]
          (let [failed (try
                         (tools/complete provider "grok-4.7" messages
                                         {:on-progress #(swap! seen conj %)})
                         false (catch Exception _ true))]
            (fact failed => true
                  @calls => []
                  (some #(string/starts-with? (:phase % "") "Running") @seen)
                  => nil)))))))

(fact "real SSE disconnect before completion never runs an announced tool"
  (with-runtime
    (fn [body]
      (fn [out]
        (write-event! out {:type "response.output_item.added"
                           :item (function-call (account-function-name body)
                                                "uncommitted-call" "{}")})
        (write-event! out {:type "response.function_call_arguments.done"
                           :arguments "{}"})))
    {:content []}
    (fn [provider requests calls]
      (let [result (try
                     (tools/complete provider "grok-4.7" messages
                                     {:on-progress identity})
                     (catch Exception e e))
            report (:ai/tool-failure (ex-data result))]
        (fact
          (.getMessage result)
          => (str "The AI provider request failed or returned "
                  "an incomplete response.")
          (:reason report) => :provider-failure
          (:budget report) => {:limit 2 :used 0 :remaining 2 :requested 0}
          (:calls report) => []
          @calls => []
          (count (filter #(= "/v1/responses" (:path %)) @requests)) => 1
          (boolean (some #(= "DELETE" (:method %)) @requests)) => true)))))

(fact "real HTTP + MCP carries mixed native and local tool state"
  (let [turns (atom 0) first-output (atom nil)]
    (with-runtime
      (fn [body]
        (if (= 1 (swap! turns inc))
          (let [name (:name (last (:tools body)))
                output (conj agentic-state
                             (function-call name "account-1" "{}"))]
            (reset! first-output output)
            (response output))
          (response [(text-output
                       "Yetibot. Source: https://x.com/example/status/1")])))
      {:content [{:type "text" :text "CLOUDFLARE_SECRET"}]
       :structuredContent {:name "Yetibot"} :isError false}
      (fn [provider requests calls]
        (let [result (tools/complete provider "grok-4.7" messages)
              turns (filter #(= "/v1/responses" (:path %)) @requests)
              first-body (:body (first turns))
              next-body (:body (second turns))
              reply (last (:input next-body))]
          (fact
            (:result/value result)
            => "Yetibot. Source: https://x.com/example/status/1"
            (get-in result [:result/data :mcp :tool-calls]) => 1
            @calls => [{:name "whoami" :arguments {}}]
            (take 2 (:tools first-body))
            => [{:type "web_search"} {:type "x_search"}]
            (take-last (count @first-output) (butlast (:input next-body)))
            => @first-output
            (:type reply) => "function_call_output"
            (:call_id reply) => "account-1"
            (get-in (json/parse-string (:output reply) true)
                    [:structuredContent :name]) => "Yetibot"
            (string/includes? (:output reply) "CLOUDFLARE_SECRET") => false
            (string/includes? (json/generate-string next-body)
                              "PROVIDER_SECRET") => false
            (mapv #(get-in % [:body :store]) turns) => [false false]
            (every? #(= ["reasoning.encrypted_content"]
                        (get-in % [:body :include])) turns) => true
            (some #(and (= "/mcp" (:path %))
                        (= "Bearer CLOUDFLARE_SECRET"
                           (get-in % [:headers "authorization"])))
                  @requests) => true))))))

(defn- command-with-admissions [request command expected]
  (let [post client/post family (atom nil)
        result (with-redefs [client/post (fn [& args]
                                          (reset! family host-execution/*request*)
                                          (apply post args))]
                 (execution/run-registered-command request command))]
    (fact (select-keys (host-execution/admissions @family) [:limit :admitted])
          => {:limit expected :admitted expected})
    result))

(fact "ordinary ai and grok roots retain config2 and deny the third physical admission without retry"
  (doseq [command ["ai --provider grok Identify us." "grok Identify us."]]
    (with-runtime
      (fn [body]
        (response [(function-call (account-function-name body) "limited-call" "{}")]))
      {:structuredContent {:name "Yetibot"} :content [] :isError false}
      (fn [_ requests calls]
        (let [request (update-in execution/request [:chat-source :raw-event] dissoc :thread_ts)
              result (command-with-admissions request command 2)]
          (fact (count (filter #(= "/v1/responses" (:path %)) @requests)) => 1
                (count @calls) => 1
                (string/includes? (:value result) "tool-call limit") => true
                (< (count (:value result)) 1500) => true))))))

(fact "explicit caller allowance3 composes real commands with named-toolset identity and model policy"
  (with-runtime
    (fn [body]
      (if (= "function_call_output" (:type (last (:input body))))
        (response [(text-output "Yetibot account.")])
        (response (conj agentic-state
                        (function-call (account-function-name body)
                                       "account-1" "{}")))))
    {:structuredContent {:name "Yetibot"} :content [] :isError false}
    (fn [provider requests calls]
      (let [request (update-in execution/request [:chat-source :raw-event]
                               dissoc :thread_ts)
            restricted
            (assoc (mcp/config)
                   :toolsets
                   [{:name "cloudflare_read" :kind "search"
                     :servers [{:name "cloudflare-read" :tools ["whoami"]}]
                     :users ["U1"] :conversations ["workspace/C1"]
                     :adapters ["slack"]
                     :providers [{:name "grok" :models ["grok-4.7"]}]}])]
        (with-redefs [config/get-config (constantly {:value {}})
                      ai/config (constantly
                                  {:providers [(assoc provider
                                                      :model "grok-4.7")]})
                      mcp/config (constantly restricted)]
          (doseq [command ["ai --provider grok" "grok"]]
            (let [command (str command
                               " --max-tool-calls 3 --toolset cloudflare_read Identify us.")]
              (fact
                (get-in (command-with-admissions request command 3)
                        [:data :mcp :tool-calls]) => 1)
              (let [before (count @requests)
                    unauthorized (-> request
                                     (assoc-in [:user :id] "U2")
                                     (assoc-in [:chat-source :raw-event :user]
                                               "U2"))]
                (fact
                  (string/includes?
                    (:error (execution/run-registered-command unauthorized command))
                    "do not have access") => true
                  (count @requests) => before))))
          (fact
            (string/includes?
              (:error (execution/run-registered-command
                        request (str "grok --model forbidden "
                                     "--toolset cloudflare_read Identify us.")))
              "provider/model is not allowed") => true
            (count @calls) => 2))))))

(fact "explicit caller allowance3 keeps legacy xAI Grok identity with real HTTP and MCP"
  (let [endpoints (atom [])
        post client/post]
    (with-runtime
      (fn [body]
        (if (= "function_call_output" (:type (last (:input body))))
          (response [(text-output "Grok account answer.")])
          (response [(function-call (account-function-name body)
                                    "account-1" "{}")])))
      {:structuredContent {:name "Yetibot"} :content []}
      (fn [provider requests calls]
        (let [request (update-in execution/request [:chat-source :raw-event]
                                 dissoc :thread_ts)]
          (with-redefs [config/get-config (constantly {:value {}})
                        xai/config {:key "LEGACY_XAI_SECRET"}
                        ai/config
                        (constantly
                          {:providers [{:name "local"
                                        :url "http://unreachable.invalid/v1"
                                        :model "qwen"}]
                           :default-provider "local"})
                        ;; Redirect only the fixed xAI endpoint to our fixture.
                        ;; MCP still uses the real SDK/HTTP transport.
                        client/post
                        (fn [url options]
                          (swap! endpoints conj url)
                          (when-not (= url "https://api.x.ai/v1/responses")
                            (throw (ex-info "Wrong provider endpoint" {})))
                          (post (str (:url provider) "/responses") options))]
            (let [result (command-with-admissions request "grok --max-tool-calls 3 Identify us." 3)
                  turns (filter #(= "/v1/responses" (:path %)) @requests)]
              (fact
                (:error result) => nil
                (:value result)
                => "Grok account answer.\n\nSent via grok-4.7"
                (get-in result [:data :provider]) => "grok"
                (get-in result [:data :mcp :tool-calls]) => 1
                (mapv #(get-in % [:body :model]) turns)
                => ["grok-4.7" "grok-4.7"]
                (take 2 (get-in (first turns) [:body :tools]))
                => [{:type "web_search"} {:type "x_search"}]
                (mapv #(get-in % [:headers "authorization"]) turns)
                => ["Bearer LEGACY_XAI_SECRET" "Bearer LEGACY_XAI_SECRET"]
                (count @endpoints) => 2
                (count @calls) => 1))))))))

(facts "invalid Responses function batches fail before any MCP call"
  (doseq [kind [:unknown :bad-json :duplicate-id
                :duplicate-action :over-budget]]
    (with-runtime
      (fn [body]
        (let [name (:name (last (:tools body)))
              call (function-call name "one" "{}")]
          (response
            (case kind
              :unknown [call (function-call "unavailable" "two" "{}")]
              :bad-json [(assoc call :arguments "not JSON")]
              :duplicate-id [call call]
              :duplicate-action [call (function-call name "two" "{}")]
              :over-budget [call
                            (function-call name "two" "{\"page\":2}")
                            (function-call name "three" "{\"page\":3}")]))))
      {:content []}
      (fn [provider _ calls]
        (let [report (try (tools/complete provider "grok-4.7" messages)
                          (catch Exception e (:ai/tool-failure (ex-data e))))]
          (fact (:reason report) => (case kind
                                     :unknown :unavailable-tool
                                     :bad-json :invalid-arguments
                                     :duplicate-id :duplicate-ids
                                     :duplicate-action :repeat-action
                                     :over-budget :budget-exceeded)
                (get-in report [:budget :used]) => 0
                (get-in report [:budget :requested])
                => (case kind :bad-json 1 :over-budget 3 2)
                (every? #(and (= :rejected (:outcome %))
                              (false? (:attempted %))) (:calls report)) => true
                @calls => []))))))

(fact "uncertain MCP outcomes stop the batch and remove native and local tools"
  (let [turns (atom 0)]
    ;; Unknown-outcome behavior is exercised through the existing execution
    ;; contract; the transport independently tests unknown HTTP outcomes.
    (with-runtime
      (fn [body]
        (if (= 1 (swap! turns inc))
          (let [name (:name (last (:tools body)))]
            (response [(function-call name "one" "{}")
                       (function-call name "two" "{\"page\":2}")]))
          (response [(text-output "The outcome is unknown.")])))
      {:content []}
      (fn [provider requests _]
        (let [attempts (atom 0)]
          (with-redefs [mcp/call-tool
                        (fn [& _]
                          (swap! attempts inc)
                          {:isError true :unknownOutcome true :content []})]
            (let [result (tools/complete provider "grok-4.7" messages)]
              (fact (get-in result [:result/data :execution :reason])
                    => :unknown-result
                    @attempts => 1
                    @turns => 1
                    (mapv :outcome
                          (get-in result [:result/data :execution :calls]))
                    => [:unknown :skipped]))))))))

(fact "at the MCP budget, the final Responses turn cannot perform native search"
  (let [turns (atom 0)]
    (with-runtime
      (fn [body]
        (if (= 1 (swap! turns inc))
          (let [name (:name (last (:tools body)))]
            (response [(function-call name "one" "{}")
                       (function-call name "two" "{\"page\":2}")]))
          (response [(text-output "Done.")])))
      {:content [] :isError false}
      (fn [provider requests calls]
        (let [result (tools/complete provider "grok-4.7" messages)
              body (last-turn requests)]
          (fact (:result/value result) => "Done."
                (count @calls) => 2
                (:tools body) => []
                (contains? body :tool_choice) => false
                (mapv :call_id (take-last 2 (:input body)))
                => ["one" "two"]))))))

(fact "configured credentials in an empty live store protect untrusted keys and scalar spellings"
  (let [turns (atom 0) stores (atom []) chat ai/chat
        opaque {:type "reasoning" :id "PROVIDER_SECRET"
                :encrypted_content "PROVIDER_SECRET" :summary []}
        usage {:input_tokens 739194 :output_tokens 1 :total_tokens 739195}
        annotated (update-in (assoc (text-output "Safe answer")
                                   :extension {:PROVIDER_SECRET "PROVIDER_SECRET" :numeric 739194 :safe "kept"}
                                   :reasoning_content "PROVIDER_SECRET") [:content 0 :annotations 0]
                             assoc (keyword "PROVIDER_SECRET") "extension"
                             :id "PROVIDER_SECRET" :key "PROVIDER_SECRET"
                             :numeric 739194 :safe "kept" :start_index 0 :end_index 11)
        source {:type "web_search_call" :id "source-item" :status "completed"
                :action {:type "search" :sources [{:type "url" :url "https://safe.example"
                                                   (keyword "PROVIDER_SECRET") "extension"
                                                   :id "PROVIDER_SECRET" :key "PROVIDER_SECRET"
                                                   :numeric 739194 :safe "kept"}]}}]
    (with-runtime
      (fn [body]
        (assoc (response (if (= 1 (swap! turns inc))
                           [opaque annotated source (function-call (account-function-name body) "safe-call" "{}")]
                           [opaque annotated source])) :usage usage))
      {:structuredContent {(keyword "PROVIDER_SECRET") "reflection"
                           :id "PROVIDER_SECRET" :key "PROVIDER_SECRET"
                           :numeric 739194 :safe "kept"}
       :content [] :isError false}
      (fn [provider requests calls]
        (let [config (update (mcp/config) :secrets conj "739194")]
          (with-redefs [mcp/config (constantly config)
                        ai/chat (fn [& args]
                                  (swap! stores conj @(get-in host-execution/*request* [:dataset-store :state]))
                                  (apply chat args))]
            (let [result (tools/complete provider "grok-4.7" messages
                                         {:actor (policy/actor host-execution/*event*)})
                  next-body (last-turn requests)
                  reply (last (:input next-body))
                  annotation (get-in result [:result/data :output 1 :content 0 :annotations 0])
                  public-source (get-in result [:result/data :output 2 :action :sources 0])
                  replay-payload (remove #(= "reasoning" (:type %)) (:input next-body))]
              (count @stores) => 2
              (every? #(and (false? (:closed? %)) (= {} (:datasets %))) @stores) => true
              @calls => [{:name "whoami" :arguments {}}]
              @turns => 2
              (:call_id reply) => "safe-call"
              (get-in (json/parse-string (:output reply) true) [:structuredContent :safe]) => "kept"
              (string/includes? (:output reply) "PROVIDER_SECRET") => false
              (string/includes? (:output reply) "739194") => false
              (doseq [payload [annotation public-source]]
                (string/includes? (json/generate-string payload) "PROVIDER_SECRET") => false
                (string/includes? (json/generate-string payload) "739194") => false
                (:safe payload) => "kept")
              (get-in result [:result/data :output 1 :extension])
              => {"[redacted]" "[redacted]" :numeric "[redacted]" :safe "kept"}
              (get-in result [:result/data :output 1 :reasoning_content]) => "[redacted]"
              (:start_index annotation) => 0
              (:end_index annotation) => 11
              (first (filter #(= "reasoning" (:type %)) (:input next-body))) => opaque
              (get-in result [:result/data :output 0]) => opaque
              (get-in result [:result/data :usage]) => usage
              (:result/value result) => "Safe answer"
              (string/includes? (json/generate-string (public/projection result)) "PROVIDER_SECRET") => false
              (string/includes? (json/generate-string replay-payload) "PROVIDER_SECRET") => false)))))))

(fact "rewritten untrusted key collisions stop before a next planner and expose only safe failure data"
  (let [turns (atom 0)]
    (with-runtime
      (fn [body]
        (swap! turns inc)
        (response [(function-call (account-function-name body) "collision-call" "{}")]))
      {:structuredContent {:PROVIDER_SECRET "one" :CLOUDFLARE_SECRET "two"} :content []}
      (fn [provider _ calls]
        (let [result (command/safely #(tools/complete provider "grok-4.7" messages
                                                    {:actor (policy/actor host-execution/*event*)}))
              report (get-in result [:result/data :tool-trace])]
          @turns => 1
          (count @calls) => 1
          (get-in result [:result/data :isError]) => true
          (get-in report [:calls 0 :outcome]) => :unknown
          (string/includes? (pr-str result) "PROVIDER_SECRET") => false
          (string/includes? (pr-str result) "CLOUDFLARE_SECRET") => false)))))


(fact "provider remainder key collisions stop actual next-planner replay without repeating an admitted tool"
  (let [turns (atom 0)]
    (with-runtime
      (fn [body]
        (swap! turns inc)
        (response [(assoc (text-output "Safe")
                          :extension {:PROVIDER_SECRET "one" :CLOUDFLARE_SECRET "two"})
                   (function-call (account-function-name body) "extension-collision" "{}")]))
      {:content [] :isError false}
      (fn [provider _ calls]
        (let [result (command/safely #(tools/complete provider "grok-4.7" messages
                                                    {:actor (policy/actor host-execution/*event*)}))]
          @turns => 1
          (count @calls) => 1
          (get-in result [:result/data :isError]) => true
          (string/includes? (pr-str result) "PROVIDER_SECRET") => false
          (string/includes? (pr-str result) "CLOUDFLARE_SECRET") => false)))))


(fact "physical Responses replay cleans malformed call positions after a command learns a new secret"
  (doseq [secret ["NEWLY-LEARNED-CREDENTIAL" 987654321]]
    (let [spelling (str secret) requests (atom []) retained (atom []) retain datasets/retain!
          malformed {:type "reasoning" :id spelling :encrypted_content spelling :summary []
                     :arguments {spelling secret :safe "kept"}
                     :tool_calls [{:id spelling :type "function" :index secret
                                   :function {:name spelling :arguments {spelling secret}}}]
                     :role spelling :tool_call_id spelling :call_id spelling :name spelling :index secret}]
      (support/with-http-server
        (fn [request]
          (swap! requests conj request)
          {:body (response (if (= 1 (count @requests))
                             [malformed (function-call "yetibot_command" spelling "{\"command\":\"learn-response-secret\"}")]
                             [(text-output "Safe answer")]))})
        (fn [base]
          (let [provider {:name "response-fixture" :model "chat" :api "responses" :url (str base "/v1")}
                t {:name "response-private" :kind "search" :servers [] :native-tools ["command"]
                   :users ["U1"] :adapters ["slack"] :providers [{:name "response-fixture" :models ["chat"]}]}]
            (with-redefs [hooks/hooks (atom @hooks/hooks) hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                          ai/config (constantly {:providers [provider]})
                          mcp/config (constantly {:servers [] :toolsets [t]})
                          context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                          datasets/retain! (fn [store owner envelope]
                                             (let [d (retain store owner envelope)]
                                               (swap! retained conj (datasets/lookup! store owner (:dataset-id d))) d))]
              (hooks/cmd-hook-resolved #"learn-response-secret" #".*"
                (fn [_] {:result/value "learned" :result/data [{:auth/token secret}]}))
              (caller/with-current-caller #(execution/run-command execution/request "ai --max-tool-calls 3 replay"))))))
      (count @requests) => 2
      (get-in (first @retained) [:rows 0 "auth/token"]) => secret
      (let [input (get-in @requests [1 :body :input])
            replay (first (filter #(= "reasoning" (:type %)) input))
            call (first (filter #(= "function_call" (:type %)) input))]
        (select-keys replay [:id :encrypted_content]) => {:id spelling :encrypted_content spelling}
        (:arguments replay) => {(keyword "[redacted]") "[redacted]" :safe "kept"}
        (:tool_calls replay) => [{:id "[redacted]" :type "function" :index "[redacted]"
                                  :function {:name "[redacted]" :arguments {(keyword "[redacted]") "[redacted]"}}}]
        (select-keys replay [:role :tool_call_id :call_id :name :index])
        => {:role "[redacted]" :tool_call_id "[redacted]" :call_id "[redacted]" :name "[redacted]" :index "[redacted]"}
        (:call_id call) => spelling
        (:name call) => "yetibot_command"))))

(fact "malformed arguments key collisions seal physical replay after the genuine tool executes once"
  (let [turns (atom 0)]
    (with-runtime
      (fn [body]
        (swap! turns inc)
        (response [{:type "reasoning" :id "collision-state" :summary []
                    :arguments {:PROVIDER_SECRET "one" :CLOUDFLARE_SECRET "two"}}
                   (function-call (account-function-name body) "typed-collision" "{}")]))
      {:content [] :isError false}
      (fn [provider _ calls]
        (let [result (command/safely #(tools/complete provider "grok-4.7" messages
                                                    {:actor (policy/actor host-execution/*event*)}))]
          @turns => 1
          (count @calls) => 1
          (get-in result [:result/data :isError]) => true
          (get-in result [:result/data :tool-trace :calls 0 :outcome]) => :success
          (string/includes? (pr-str result) "PROVIDER_SECRET") => false
          (string/includes? (pr-str result) "CLOUDFLARE_SECRET") => false)))))


(fact "provider supplied result envelope keys are projected after one real command learns credentials"
  (doseq [mode [:string :numeric :both :collision]]
    (let [collision? (boolean (#{:both :collision} mode))
          requests (atom []) retained (atom []) commands (atom 0) families (atom [])
          retain datasets/retain! post client/post
          private "ENVELOPE-LEARNED-STRING" numeric 987654321
          payload (merge {:safe "kept"}
                         (case mode
                           :string {private "string-key"}
                           :numeric {(str numeric) "numeric-key"}
                           :both {private "string-key" (str numeric) "numeric-key"}
                           :collision {private "string-key" "[redacted]" "collision"}))
          supplied {:type "function_call_output" :call_id private
                    :output (json/generate-string payload)}
          opaque {:type "reasoning" :id private :encrypted_content private :summary []}]
      (support/with-http-server
        (fn [request]
          (swap! requests conj request)
          {:body (response (if (= 1 (count @requests))
                             [opaque supplied (function-call "yetibot_command" private
                                                             "{\"command\":\"learn-envelope-secret\"}")]
                             [(text-output "Safe answer")]))})
        (fn [base]
          (let [provider {:name "envelope-fixture" :model "chat" :api "responses" :url (str base "/v1")}
                t {:name "envelope-private" :kind "search" :servers [] :native-tools ["command"]
                   :users ["U1"] :adapters ["slack"]
                   :providers [{:name "envelope-fixture" :models ["chat"]}]}]
            (with-redefs [hooks/hooks (atom @hooks/hooks) hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                          ai/config (constantly {:providers [provider]})
                          mcp/config (constantly {:servers [] :toolsets [t]})
                          context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                          client/post (fn [& args]
                                        (swap! families conj host-execution/*request*)
                                        (apply post args))
                          datasets/retain! (fn [store owner envelope]
                                             (let [d (retain store owner envelope)]
                                               (swap! retained conj (datasets/lookup! store owner (:dataset-id d))) d))]
              (hooks/cmd-hook-resolved #"learn-envelope-secret" #".*"
                (fn [_] (swap! commands inc)
                  {:result/value "learned" :result/data [{:auth/token private :auth/key numeric}]}))
              (let [result (execution/run-registered-command execution/request "ai --max-tool-calls 3 replay")]
                (when collision?
                  (get-in result [:data :isError]) => true
                  (get-in result [:data :tool-trace :calls 0 :outcome]) => :success))))))
      @commands => 1
      (get-in (first @retained) [:rows 0 "auth/token"]) => private
      (get-in (first @retained) [:rows 0 "auth/key"]) => numeric
      (:used @(:budget (first @families))) => (if collision? 2 3)
      (count @requests) => (if collision? 1 2)
      (when-not collision?
        (let [input (get-in @requests [1 :body :input])
              replay (first (filter #(= "function_call_output" (:type %)) input))
              call (first (filter #(= "function_call" (:type %)) input))]
          (:call_id replay) => private
          (string/includes? (:output replay) private) => false
          (string/includes? (:output replay) (str numeric)) => false
          (:safe (json/parse-string (:output replay) true)) => "kept"
          (:call_id call) => private
          (:name call) => "yetibot_command"
          (first (filter #(= "reasoning" (:type %)) input)) => opaque)))))
