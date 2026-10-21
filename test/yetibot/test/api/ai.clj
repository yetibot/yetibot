(ns yetibot.test.api.ai
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.core.chat :as chat]
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
      (ai/complete provider "other/model" messages)
      => {:result/value "answer" :result/data body}
      (first @captured) => "https://example.com/custom/v1/chat/completions"
      (select-keys (second @captured) [:as :content-type :form-params :headers
                                      :redirect-strategy :conn-timeout :socket-timeout])
      => {:as :json :content-type :json
          :form-params {:model "other/model" :messages messages}
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
            => "AI provider example returned no text response.")))
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
          => {:model "model-a" :messages messages}
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
