(ns yetibot.test.integration.ai-responses
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.grok]
            [yetibot.core.config :as config]
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
          {:body (turn body)}
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
                        (function-call (:name (last (:tools body)))
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
                  (:tool_choice (last-turn requests)) => "none")))))))

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
                (:tool_choice body) => "none"
                (mapv :call_id (take-last 2 (:input body)))
                => ["one" "two"]))))))
