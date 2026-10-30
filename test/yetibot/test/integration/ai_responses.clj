(ns yetibot.test.integration.ai-responses
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.grok]
            [yetibot.core.config :as config]
            [yetibot.core.util.xai :as xai]
            [yetibot.models.ai-tools :as tools]
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
          (with-redefs [mcp/config (constantly config)]
            (f provider requests calls)))))))

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
                            (when (:answer event)
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
              => ["I have a lead." "Yetibot."])))))))

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
                     (catch Exception e (.getMessage e)))]
        (fact
          result
          => "The AI provider returned an invalid or incomplete event stream."
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

(fact "both real commands enforce named-toolset identity and model policy"
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
                               " --toolset cloudflare_read Identify us.")]
              (fact
                (get-in (execution/run-command request command)
                        [:data :mcp :tool-calls]) => 1)
              (let [before (count @requests)
                    unauthorized (-> request
                                     (assoc-in [:user :id] "U2")
                                     (assoc-in [:chat-source :raw-event :user]
                                               "U2"))]
                (fact
                  (string/includes?
                    (:error (execution/run-command unauthorized command))
                    "do not have access") => true
                  (count @requests) => before))))
          (fact
            (string/includes?
              (:error (execution/run-command
                        request (str "grok --model forbidden "
                                     "--toolset cloudflare_read Identify us.")))
              "provider/model is not allowed") => true
            (count @calls) => 2))))))

(fact "legacy xAI credentials keep Grok identity with real HTTP and MCP"
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
            (let [result (execution/run-command request "grok Identify us.")
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
        (fact (try (tools/complete provider "grok-4.7" messages)
                   (catch Exception e (:ai/error (ex-data e)))) => true
              @calls => [])))))

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
            (fact (:result/value (tools/complete provider "grok-4.7" messages))
                  => "The outcome is unknown."
                  @attempts => 1
                  (:tools (last-turn requests)) => []
                  (contains? (last-turn requests) :tool_choice) => false)))))))

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
