(ns yetibot.test.api.ai
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai]
            [yetibot.models.ai-tools :as tools]
            [yetibot.core.util.agent-execution :as origin]
            [yetibot.test.support.mcp :as support]
            [yetibot.core.chat :as chat]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.util.ai-execution :as execution]
            [yetibot.core.config :as config]
            [yetibot.models.ai-context :as context])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))

(def provider {:name "example" :url "https://example.com/custom/v1/" :key "secret" :model "chat"})
(def messages [{:role "user" :content "hello"}])

(defn error-message [f]
  (try (f) (catch Exception e (.getMessage e))))

(facts "configuration accepts named providers and local endpoints"
  (s/valid? ::ai/config {:providers [provider {:name "local" :url "http://localhost:1234/v1"}]
                        :default-provider "local"}) => true
  (doseq [invalid [{:providers []}
                   {:providers [provider provider]}
                   {:providers [(dissoc provider :name)]}
                   {:providers [(assoc provider :name "two words")]}
                   {:providers [(assoc provider :url "not a URL")]}
                   {:providers [(assoc provider :url "https://key@example.com/v1")]}
                   {:providers [(assoc provider :url "https://example.com/v1?key=secret")]}
                   {:providers [(assoc provider :url "file:///tmp/models")]}
                   {:providers [provider] :default-provider "missing"}]]
    (fact (s/valid? ::ai/config invalid) => false)))

(facts "configuration failures never reveal spec details or secrets"
  (with-redefs [config/get-config (constantly {:error :invalid :message "secret value"})]
    (error-message ai/config)
    => "AI configuration is invalid. Check provider names, URLs and defaults.")
  (with-redefs [config/get-config (constantly {:error :not-found})]
    (error-message ai/config) => "AI is not configured. Configure ai.providers first."))

(fact "chat requests use the selected endpoint, key, model and full message list"
  (let [captured (atom nil)
        body {:choices [{:message {:content "answer"}}] :usage {:total_tokens 12}}]
    (with-redefs [client/post (fn [url options]
                               (reset! captured [url options])
                               {:status 200 :body body})]
      (-> (ai/complete provider "other/model" messages)
          (update :result/data dissoc :ai/accounting)
          (dissoc :result/public-data))
      => {:result/value "answer" :result/data body}
      (first @captured) => "https://example.com/custom/v1/chat/completions"
      (select-keys (second @captured) [:as :content-type :form-params :headers
                                      :redirect-strategy :conn-timeout :socket-timeout])
      => {:as :json :content-type :json
          :form-params {:model "other/model" :messages messages
                        :max_completion_tokens 32768}
          :headers {"Accept" "application/json" "Authorization" "Bearer secret"}
          :redirect-strategy :none :conn-timeout 5000 :socket-timeout 120000}
      ((:retry-handler (second @captured)) nil nil nil) => false)))

(fact "model requests use each provider's URL and omit auth for keyless endpoints"
  (let [captured (atom nil)]
    (with-redefs [client/get (fn [url options]
                              (reset! captured [url options])
                              {:status 200 :body {:data [{:id "z"} {:id "a"} {:id "z"}]}})]
      (ai/models (dissoc provider :key)) => ["a" "z"]
      (first @captured) => "https://example.com/custom/v1/models"
      (:headers (second @captured)) => {"Accept" "application/json"})))

(facts "failures produce safe errors, not provider response bodies"
  (doseq [status [301 401 429 500]]
    (with-redefs [client/post (fn [& _] {:status status :body {:error {:message "secret"}}})]
      (fact (error-message #(ai/complete provider "chat" messages))
            => (str "AI provider example returned HTTP " status "."))))
  (with-redefs [client/post (fn [& _] (throw (Exception. "secret request and headers")))]
    (error-message #(ai/complete provider "chat" messages))
    => "Could not reach AI provider example or read its response.")
  (doseq [body [nil {} {:choices []} {:choices [{:message {:content nil}}]}
               {:choices [{:message {:content " "}}]}]]
    (with-redefs [client/post (fn [& _] {:status 200 :body body})]
      (fact (error-message #(ai/complete provider "chat" messages))
            => "The AI provider returned an invalid text response.")))
  (doseq [body [nil {} {:data [{:id nil}]} {:data "bad"}]]
    (with-redefs [client/get (fn [& _] {:status 200 :body body})]
      (fact (error-message #(ai/models provider))
            => "AI provider example returned an invalid model list."))))

(fact "real HTTP requests use compatible JSON and Slack query parameters without following redirects"
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (let [path (.getPath (.getRequestURI exchange))
                request {:path path
                         :query (.getQuery (.getRequestURI exchange))
                         :auth (.getFirst (.getRequestHeaders exchange) "Authorization")
                         :body (slurp (.getRequestBody exchange))}
                [status body] (case path
                                "/custom/v1/models" [200 {:data [{:id "model-a"}]}]
                                "/custom/v1/chat/completions" [200 {:choices [{:message {:content "answer"}}]}]
                                "/slack/conversations.replies" [200 {:ok true :messages [{:ts "1.0" :user "person" :text "root"}]}]
                                [302 {}])
                bytes (.getBytes (json/generate-string body) "UTF-8")]
            (swap! requests conj request)
            (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
            (.set (.getResponseHeaders exchange) "Location" "/must-not-follow")
            (.sendResponseHeaders exchange status (count bytes))
            (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.start server)
    (try
      (let [base (str "http://127.0.0.1:" (.getPort (.getAddress server)))
            provider (assoc provider :url (str base "/custom/v1/"))]
        (fact
          (ai/models provider) => ["model-a"]
          (:result/value (ai/complete provider "model-a" messages)) => "answer"
          (:auth (first @requests)) => "Bearer secret"
          (json/parse-string (:body (second @requests)) true)
          => {:model "model-a" :messages messages :max_completion_tokens 32768}
          (binding [chat/*adapter* {:config {:endpoint (str base "/slack") :token "slack-secret"}}]
            (context/messages {:chat-source {:adapter :slack
                                             :raw-event {:channel "C1" :thread_ts "1.0" :ts "2.0"}}}
                              "current"))
          => [{:role "user" :content "person: root"} {:role "user" :content "current"}]
          (boolean (re-find #"limit=100" (:query (nth @requests 2)))) => true
          (error-message #(ai/models (assoc provider :url (str base "/redirect"))))
          => "AI provider example returned HTTP 302."
          (mapv :path @requests)
          => ["/custom/v1/models" "/custom/v1/chat/completions" "/slack/conversations.replies" "/redirect/models"]))
      (finally (.stop server 0)))))


(fact "actual prepared chat and Responses wire facts precede each physical send"
  (doseq [api [nil "responses"]]
    (let [p (cond-> (assoc provider :url "https://routed.invalid/private/v1/")
              api (assoc :api api))
          admissions (atom []) wires (atom [])
          response (if api {:status "completed" :output [{:type "message"
                                    :content [{:type "output_text" :text "answer"}]}]}
                       {:choices [{:message {:role "assistant" :content "answer"}}]})]
      (with-redefs [client/post (fn [url opts]
                                 (swap! wires conj [url (:form-params opts)])
                                 (count @admissions) => (count @wires)
                                 {:status 200 :body response})]
        (binding [usage/*admit-inference* #(do (swap! admissions conj %) {:ordinal 1})
                  usage/*finish-inference* (fn [_ _])]
          (ai/chat p "chat" messages {:max-output-tokens 91}) => response)
        (select-keys (first @admissions) [:provider :model :provider-signature :output-cap])
        => {:provider "example" :model "chat"
            :provider-signature (execution/provider-signature p) :output-cap 91}
        (:endpoint (first @admissions)) => (ffirst @wires)
        (get-in @wires [0 1 (if api :max_output_tokens :max_completion_tokens)]) => 91
        (binding [usage/*admit-inference* (fn [_] (throw (ex-info "denied" {})))]
          (ai/chat p "other" messages {:max-output-tokens 91})) => (midje.sweet/throws Exception)
        (count @wires) => 1
        (boolean (re-find #"secret" (pr-str (first @admissions)))) => false))))

(fact "installed host raw SDK Responses preserves native and function wire definitions and valid root caps"
  (let [requests (atom []) admissions (atom [])
        response {:status "completed" :output [{:type "message" :content [{:type "output_text" :text "bounded"}]}]}
        function {:type "function" :function {:name "wire_only" :description "Not a host tool"
                                               :parameters {:type "object" :properties {}}}}]
    (support/with-http-server
      (fn [request]
        (swap! requests conj request)
        (fact (count @admissions) => (count @requests))
        {:body response})
      (fn [url]
        (let [p {:name "raw-sdk" :url (str url "/v1") :api "responses"
                 :native-tools ["web_search" "x_search"]}]
          (with-redefs [config/get-config (constantly {:error :not-found})
                        mcp/config (constantly {:servers []})]
            (fact (origin/host-active?) => true)
            (binding [usage/*admit-inference* #(do (swap! admissions conj %) nil)]
              (doseq [cap [2048 120 65536 262144]]
                (fact (ai/chat p "model" messages {:tools [function] :max-output-tokens cap}) => response))
              (fact (ai/chat p "model" messages {:tools [function] :tool_choice "none"}) => response))
            (fact (mapv #(get-in % [:body :max_output_tokens]) @requests) => [2048 120 65536 262144 32768]
                  (get-in @requests [0 :body :tools])
                  => [{:type "web_search"} {:type "x_search"} (assoc (:function function) :type "function")]
                  (get-in @requests [4 :body :tools]) => [])
            (doseq [cap [0 -1 262145 "65536" 1.5]]
              (fact (ai/chat p "model" messages {:max-output-tokens cap}) => (midje.sweet/throws Exception)))
            (fact (count @requests) => 5)))))))

(fact "installed host raw SDK progress cannot execute host work or reset its live finite Context"
  (let [requests (atom []) captured (atom nil) progress (atom 0)
        response {:status "completed" :output [{:type "message" :content [{:type "output_text" :text "bounded"}]}]}
        write! (fn [out event]
                 (.write ^java.io.OutputStream out (.getBytes (str "data: " (json/generate-string event) "\n\n") "UTF-8"))
                 (.flush ^java.io.OutputStream out))]
    (support/with-http-server
      (fn [request]
        (swap! requests conj request)
        (if (get-in request [:body :stream])
          {:stream (fn [out]
                     (write! out {:type "response.output_item.done" :output_index 0 :item (first (:output response))})
                     (write! out {:type "response.completed" :response response}))}
          {:body response}))
      (fn [url]
        (let [p {:name "raw-progress" :url (str url "/v1") :api "responses" :native-tools ["web_search"]}]
          (with-redefs [config/get-config (constantly {:error :not-found})
                        mcp/config (constantly {:servers []})]
            (fact
              (ai/chat p "model" messages
                {:max-tool-calls 2 :max-output-tokens 91
                 ;; Serialized options cannot turn a wire schema into rights.
                 :no-host-execution? false :actor {:user "admin"} :transport-capability true
                 :on-progress
                 (fn [_]
                   (when (= 1 (swap! progress inc))
                     (reset! captured origin/*context*)
                     (fact (origin/capability? @captured) => true
                           (execution/admissions execution/*request*)
                           => {:limit 2 :admitted 1 :reserved 0 :remaining 1})
                     (doseq [[action args] [[:start [{:match ["agent run work" "work"]}]] [:execute-command [{:match ["echo work" "echo work"]}]] [:admit-command [{:cmd "echo" :args "work"}]]]]
                       (fact (apply origin/invoke! @captured action args) => (midje.sweet/throws Exception)))
                     (fact (tools/complete p "model" messages) => (midje.sweet/throws Exception))
                     (doseq [[changed model options] [[(assoc p :url (str url "/other")) "model" {}]
                                                       [(assoc p :native-tools ["x_search"]) "model" {}]
                                                       [p "other-model" {}]
                                                       [p "model" {:max-output-tokens 92}]
                                                       [(assoc p :url (str url "/other")) "model"
                                                        {:producer-operation? true :non-chat? true :no-tools false}]]]
                       (fact (ai/chat changed model messages options) => (midje.sweet/throws Exception)))
                     (let [resolutions (atom 0) resolve-provider ai/resolve-provider]
                       (with-redefs [ai/resolve-provider
                                     (fn [provider] (swap! resolutions inc) (resolve-provider provider))]
                         (fact (ai/complete (assoc p :url (str url "/other") :key-file "/unread/key")
                                            "model" messages {:producer-operation? true})
                               => (midje.sweet/throws Exception)
                               @resolutions => 0)))
                     ;; Losing only the app binding must recover the SAME core
                     ;; family, not mint another root or larger generation cap.
                     (binding [execution/*request* nil]
                       (fact (ai/chat p "model" messages {:max-output-tokens 91}) => response
                             (ai/chat p "model" messages {:max-output-tokens 91}) => (midje.sweet/throws Exception)))
                     (fact (execution/admissions execution/*request*)
                           => {:limit 2 :admitted 2 :reserved 0 :remaining 0})))})
              => response
              (count @requests) => 2
              (pos? @progress) => true)
            (binding [execution/*request* nil origin/*context* @captured]
              (fact (ai/chat p "model" messages {}) => (midje.sweet/throws Exception)))
            (binding [origin/*context* {:callbacks {:transport true}}]
              (fact (ai/chat p "model" messages {}) => (midje.sweet/throws Exception)))
            (fact (count @requests) => 2)))))))

(fact "installed host raw SDK configured identity revocation and malformed configuration fail before credential or wire IO"
  (let [requests (atom []) credentials (atom 0)
        p {:name "configured-sdk" :url "http://127.0.0.1:9/v1" :model "model" :key-file "/unread/fixture-key"}
        resolve-provider ai/resolve-provider]
    (with-redefs [mcp/config (constantly {:servers []})
                  ai/config (constantly {:providers [p]})
                  ai/resolve-provider (fn [provider] (swap! credentials inc) (resolve-provider provider))
                  client/post (fn [& _] (swap! requests conj :forbidden))]
      (doseq [changed [(assoc p :url "http://127.0.0.1:9/changed")
                       (assoc p :api "responses")
                       (assoc p :native-tools ["web_search"])]]
        (fact (ai/chat changed "model" messages {}) => (midje.sweet/throws Exception)))
      (fact @credentials => 0 @requests => [])
      (with-redefs [ai/config (fn [] (throw (ex-info "Malformed fixture configuration" {})))
                    config/get-config (constantly {:error :invalid})]
        (fact (ai/complete p "model" messages) => (midje.sweet/throws Exception)))
      (fact @credentials => 0 @requests => []))))

(fact "installed host raw SDK cannot reclassify revoked configured sources or reuse unknown Contexts"
  (doseq [mode [:revoked :policy :unknown]]
    (let [requests (atom []) checks (atom 0)
          response {:status "completed" :output [{:type "message" :content [{:type "output_text" :text "bounded"}]}]}
          write! (fn [out event]
                   (.write ^java.io.OutputStream out (.getBytes (str "data: " (json/generate-string event) "\n\n") "UTF-8"))
                   (.flush ^java.io.OutputStream out))]
      (support/with-http-server
        (fn [request]
          (swap! requests conj request)
          {:stream (fn [out]
                     (write! out {:type "response.output_item.done" :output_index 0 :item (first (:output response))})
                     (write! out {:type "response.completed" :response response}))})
        (fn [url]
          (let [p {:name "configured-live-sdk" :url (str url "/v1") :api "responses"}
                providers (atom [p]) policy (atom {:servers []})
                send! #(ai/chat p "model" messages
                         {:on-progress
                          (fn [_]
                            (when (= 1 (swap! checks inc))
                              (case mode
                                :revoked (reset! providers [])
                                :policy (reset! policy {:servers [] :toolsets [{:name "restricted" :users ["other"]}]})
                                :unknown (execution/finish-operation! origin/*operation* {:state :unknown}))
                              (binding [execution/*request* nil]
                                (fact (ai/chat p "model" messages {}) => (midje.sweet/throws Exception)))))} )]
            (with-redefs [ai/config (fn [] {:providers @providers})
                          mcp/config (fn [] @policy)]
              (if (= mode :unknown)
                (fact (send!) => (midje.sweet/throws Exception))
                (fact (send!) => response))
              (fact (pos? @checks) => true
                    (count @requests) => 1))))))))
