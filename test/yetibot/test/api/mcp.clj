(ns yetibot.test.api.mcp
  (:require [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [dec :as dec]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-mcp-catalog :as catalog]
            [yetibot.api.ai :as ai]
            [yetibot.commands.ai :as command]
            [yetibot.core.config :as config]
            [yetibot.test.support.mcp :as support]))

(def server {:name "search" :url "https://search.example/mcp" :tools ["web_search"]})
(defn error-message [f] (try (f) (catch Exception e (.getMessage e))))

(facts "MCP settings accept discovery or explicit tools on a trusted connection"
  (s/valid? ::mcp/config {:servers [server] :max-tool-calls "4" :timeout-ms "5000"}) => true
  (s/valid? ::mcp/config {:servers [{:name "local" :command "node" :args ["server.js"]
                                    :env {"API_KEY" "secret"} :tools ["search"]}]}) => true
  (s/valid? ::mcp/config {:servers []}) => true
  (s/valid? ::mcp/config {:servers [(dissoc server :tools)]}) => true
  (s/valid? ::mcp/config
            {:servers [{:name "local" :command "node"}]}) => true
  (doseq [settings [{:servers [server server]}
                    {:servers [(assoc server :tools nil)]}
                    {:servers [(assoc server :tools [])]}
                    {:servers [(assoc server :tools ["search" "search"])]}
                    {:servers [(assoc server :command "sh")]}
                    {:servers [(dissoc server :url)]}
                    {:servers [(assoc server :url "https://user:secret@example.com/mcp")]}
                    {:servers [(assoc server :url "https://example.com/mcp?key=secret")]}
                    {:servers [server] :max-tool-calls 0}
                    {:servers [server] :max-tool-calls 21}
                    {:servers [server] :timeout-ms "bad"}]]
    (fact (s/valid? ::mcp/config settings) => false)))

(fact "documented environment settings produce valid MCP configuration"
  (let [settings (get-in (dec/explode
                          {:yetibot-ai-mcp-servers-0-name "tavily"
                           :yetibot-ai-mcp-servers-0-url "https://mcp.tavily.com/mcp/"
                           :yetibot-ai-mcp-servers-0-headers-authorization "Bearer test-key"
                           :yetibot-ai-mcp-servers-0-tools-0 "tavily-search"
                           :yetibot-ai-mcp-max-tool-calls "6"}) [:yetibot :ai :mcp])]
    (fact (s/valid? ::mcp/config settings) => true)))

(facts "optional settings and invalid settings do not leak credentials"
  (with-redefs [config/get-config (constantly {:error :not-found})]
    (mcp/config) => nil)
  (with-redefs [config/get-config (constantly {:error :invalid :message "secret-spec-data"})]
    (error-message mcp/config)
    => "AI tool configuration is invalid. Check MCP server connections and allowed tool names.")
  (mcp/redact "Bearer private-token and private-token and another-secret"
              {:servers [(assoc server :headers {"Authorization" "Bearer private-token"}
                                :env {"KEY" "another-secret"})]})
  => "[redacted] and [redacted] and [redacted]"
  (mcp/redact "answer" {:servers [(assoc server :headers {"Authorization" "Bearer "})]}) => "answer")

(fact "metadata redaction handles mixed JSON escapes before truncation"
  (let [settings {:secrets ["quote\"/secret"]}]
    (fact (mcp/metadata-redact "quote\\\"\\/secr\\u0065t" settings)
          => "[redacted]"
          (mcp/metadata-text "prefix quote\"/secret suffix" settings 12)
          => {:text "prefix [reda" :truncated true})))

(fact "compiled redactors remain local to their credential snapshot"
  (let [compile-pattern re-pattern
        compilations (atom 0)
        redact (with-redefs [clojure.core/re-pattern
                             (fn [text]
                               (swap! compilations inc)
                               (compile-pattern text))]
                 (mcp/metadata-redactor {:secrets ["quote\"/secret"]}))
        other (mcp/metadata-redactor {:secrets ["other-secret"]})]
    (redact "quote\\\"\\/secr\\u0065t other-secret")
    => "[redacted] other-secret"
    (redact "quote\"/secret") => "[redacted]"
    @compilations => 1
    (other "quote\"/secret other-secret") => "quote\"/secret [redacted]"
    (mcp/metadata-redact "quote\"/secret other-secret"
                         {:secrets ["other-secret"]})
    => "quote\"/secret [redacted]"))

(facts "public GitHub transport controls are not mistaken for credentials"
  (let [s (assoc server :tools ["get_commit"]
                        :headers {"Authorization" "Bearer private-token"
                                  "X-MCP-Readonly" "true" "X-MCP-Tools" "get_commit"})]
    (fact (mcp/redact "true get_commit private-token" {:servers [s]})
          => "true get_commit [redacted]"
          (mcp/redact "private-token" {:servers [(assoc s :headers {"X-MCP-Readonly" "private-token"})]})
          => "[redacted]"
          (mcp/redact "private-token" {:servers [(assoc s :headers {"X-MCP-Tools" "private-token"})]})
          => "[redacted]"
          (mcp/redact "true" {:servers [(assoc s :github-api-token "true")]})
          => "[redacted]")))

(facts "HTTP MCP supports JSON and SSE responses, pagination, allowlists and cleanup"
  (doseq [sse [false true]]
    (let [requests (atom [])]
      (support/with-http-server (support/rpc-handler requests sse)
        (fn [base]
          (mcp/with-tools {:servers [(assoc server :url (str base "/nested/mcp")
                                                  :headers {"Authorization" "Bearer mcp-secret"})]}
            (fn [tools]
              (fact (mapv :name tools) => ["web_search"])
              (fact (get-in (first tools) [:inputSchema :properties :query :type]) => "string")
              (fact (:isError (mcp/call-tool (:session (first tools)) "web_search" {"query" "news"})) => false)
              (fact (error-message #(mcp/call-tool (:session (first tools)) "delete_all" {}))
                    => "This MCP tool is not allowed by the server configuration.")))
          (fact "the endpoint path, auth and session are preserved without exposing other tools"
            (every? #(= "/nested/mcp" (:path %)) @requests) => true
            (every? #(= "Bearer mcp-secret" (get-in % [:headers "authorization"])) @requests) => true
            (some #(= "DELETE" (:method %)) @requests) => true
            (->> @requests (keep #(get-in % [:body :method])) frequencies)
            => {"initialize" 1 "notifications/initialized" 1 "tools/list" 2 "tools/call" 1}))))))

(fact "initialize instructions are bounded data, never applied instructions"
  (let [requests (atom []) rpc (support/rpc-handler requests false)
        secret "private-token"]
    (support/with-http-server
      (fn [request]
        (let [response (rpc request)]
          (if (= "initialize" (get-in request [:body :method]))
            (assoc-in response [:body :result :instructions]
                      (str secret (string/join (repeat 25000 "x"))))
            response)))
      (fn [base]
        (mcp/with-tools
          {:servers [(assoc server :url base
                                   :headers {"Authorization"
                                             (str "Bearer " secret)})]}
          (fn [tools]
            (let [instruction (get-in (first tools)
                                      [:session :instructions])]
              (fact (:status instruction) => "UNTRUSTED/NOT APPLIED TO MODEL"
                    (:truncated instruction) => true
                    (count (:text instruction)) => 20000
                    (.contains (str instruction) secret) => false))))
        (fact (->> @requests (keep #(get-in % [:body :method]))
                   frequencies)
              => {"initialize" 1 "notifications/initialized" 1
                  "tools/list" 2}
              (some #(= "DELETE" (:method %)) @requests) => true)))))

(fact "empty discovery retains initialize metadata and closes its one session"
  (let [requests (atom []) rpc (support/rpc-handler requests false)]
    (support/with-http-server
      (fn [request]
        (let [response (rpc request)]
          (case (get-in request [:body :method])
            "initialize"
            (assoc-in response [:body :result :instructions] "Not a prompt")
            "tools/list" (assoc response :body {:jsonrpc "2.0"
                                              :id (get-in request [:body :id])
                                              :result {:tools []}})
            response)))
      (fn [base]
        (mcp/with-tools {:servers [(dissoc (assoc server :url base) :tools)]}
          (fn [tools]
            (fact tools => []
                  (::mcp/instructions (meta tools))
                  => [{:server "search"
                       :instructions
                       {:text "Not a prompt"
                        :status "UNTRUSTED/NOT APPLIED TO MODEL"}}])))
        (fact (->> @requests (keep #(get-in % [:body :method]))
                   frequencies)
              => {"initialize" 1 "notifications/initialized" 1
                  "tools/list" 1}
              (some #(= "DELETE" (:method %)) @requests) => true)))))

(fact "URL-only discovery spans pages and binds calls to advertised tools"
  (doseq [sse [false true]]
    (let [requests (atom [])]
      (support/with-http-server (support/rpc-handler requests sse)
        (fn [base]
          (mcp/with-tools
            {:servers [{:name "devth" :url (str base "/mcp")}]}
            (fn [tools]
              (fact (mapv :name tools) => ["web_search" "delete_all"]
                    (:isError (mcp/call-tool (:session (first tools))
                                            "web_search" {})) => false
                    (string? (error-message
                               #(mcp/call-tool (:session (first tools))
                                               "not_advertised" {}))) => true)))
          (fact (count (filter #(= "tools/call"
                                   (get-in % [:body :method])) @requests)) => 1
                (some #(= "DELETE" (:method %)) @requests) => true))))))

(fact "ambiguous discovered names fail closed and close the session"
  (let [requests (atom []) rpc (support/rpc-handler requests false)
        exposed? (atom false)]
    (support/with-http-server
      (fn [request]
        (let [response (rpc request)]
          (if (= "tools/list" (get-in request [:body :method]))
            (assoc-in response [:body :result :tools] [support/search-tool])
            response)))
      (fn [base]
        (fact
          (error-message
            #(mcp/with-tools {:servers [{:name "search" :url base}]}
               (fn [_] (reset! exposed? true))))
          => "Could not list allowed tools on MCP server search."
          @exposed? => false
          (some #(= "DELETE" (:method %)) @requests) => true)))))

(fact "stdio MCP can discover tools without a hand-written allowlist"
  (mcp/with-tools
    {:servers [{:name "local" :command "node"
                :args [(.getAbsolutePath
                         (io/file "test/fixtures/mcp_server.mjs"))]}]}
    (fn [tools]
      (fact (some #{"web_search"} (map :name tools)) => "web_search"
            (:isError (mcp/call-tool (:session (first tools))
                                    "web_search" {"query" "news"})) => false))))

(fact "a local stdio MCP server receives only explicit keys and the SDK's safe environment"
  (mcp/with-tools {:servers [{:name "local" :command "node"
                             :args [(.getAbsolutePath (io/file "test/fixtures/mcp_server.mjs"))]
                             :env {"MCP_TEST_KEY" "fake-mcp-key"} :tools ["web_search"]}]}
    (fn [tools]
      (let [result (mcp/call-tool (:session (first tools)) "web_search" {"query" "current news"})
            content (json/parse-string (get-in result [:content 0 :text]) true)]
        (fact content => {:query "current news" :explicit_key_present true :bot_secrets_inherited false})))))

(fact "sessions close if discovery or the model fails"
  (let [closed (atom [])]
    (with-redefs [mcp/connect! (fn [server _] {:server server})
                  mcp/close! #(swap! closed conj (get-in % [:server :name]))
                  mcp/list-tools (fn [_] (mcp/fail "bad discovery"))]
      (error-message #(mcp/with-tools {:servers [server]} identity)) => "bad discovery"
      @closed => ["search"])
    (reset! closed [])
    (with-redefs [mcp/connect! (fn [server _] (if (= "offline" (:name server))
                                             (mcp/fail "offline") {:server server}))
                  mcp/close! #(swap! closed conj (get-in % [:server :name]))]
      (error-message #(mcp/with-tools {:servers [server (assoc server :name "offline")]} identity)) => "offline"
      @closed => ["search"])
    (reset! closed [])
    (with-redefs [mcp/connect! (fn [server _] {:server server})
                  mcp/close! #(swap! closed conj (get-in % [:server :name]))
                  mcp/list-tools (constantly [])]
      (error-message #(mcp/with-tools {:servers [server]} (fn [_] (throw (Exception. "model failed")))))
      => "model failed"
      @closed => ["search"])))

(facts "missing tools and HTTP failures fail safely"
  (let [requests (atom [])]
    (support/with-http-server (support/rpc-handler requests false)
      (fn [base]
        (fact (error-message #(mcp/with-tools {:servers [(assoc server :url (str base "/mcp") :tools ["missing"])]} identity))
              => "Could not list allowed tools on MCP server search."))))
  (support/with-http-server (fn [_] {:status 401 :body {:error "credential-secret"}})
    (fn [base]
      (fact (error-message #(mcp/with-tools {:servers [(assoc server :url (str base "/mcp"))] :timeout-ms 1000} identity))
            => "Could not connect to MCP server search. Check its connection and credentials."))))

(facts "failed requests, timeouts and bad pagination are bounded and never replayed"
  (let [requests (atom []) rpc (support/rpc-handler requests false)]
    (support/with-http-server
      (fn [request]
        (if (= "tools/call" (get-in request [:body :method]))
          (do (swap! requests conj request) {:status 500 :body {:error "secret-server-details"}})
          (rpc request)))
      (fn [base]
        (mcp/with-tools {:servers [(assoc server :url (str base "/mcp"))] :timeout-ms 1000}
          (fn [tools]
            (let [result (mcp/call-tool (:session (first tools)) "web_search" {})]
              (fact (:isError result) => true)
              (fact (boolean (re-find #"outcome is unknown" (get-in result [:content 0 :text]))) => true))))
        (fact (count (filter #(= "tools/call" (get-in % [:body :method])) @requests)) => 1))))
  (let [requests (atom []) rpc (support/rpc-handler requests false)]
    (support/with-http-server
      (fn [request]
        (let [response (rpc request)]
          (if (= "tools/list" (get-in request [:body :method]))
            (assoc-in response [:body :result :nextCursor] "repeated") response)))
      (fn [base]
        (fact (error-message #(mcp/with-tools {:servers [(assoc server :url (str base "/mcp"))]} identity))
              => "Could not list allowed tools on MCP server search.")
        (fact (count (filter #(= "tools/list" (get-in % [:body :method])) @requests)) => 2)))))

(fact "HTTP redirects are not followed with MCP credentials"
  (let [requests (atom [])]
    (support/with-http-server
      (fn [request]
        (swap! requests conj request)
        {:status 307 :headers {"Location" "/must-not-follow"}})
      (fn [base]
        (fact (error-message #(mcp/with-tools {:servers [(assoc server :url (str base "/mcp")
                                                                     :headers {"Authorization" "Bearer secret"})]
                                               :timeout-ms 1000} identity))
              => "Could not connect to MCP server search. Check its connection and credentials.")
        (fact (every? #(= "/mcp" (:path %)) @requests) => true)))))

(fact "initialization times out instead of waiting indefinitely"
  (support/with-http-server (fn [_] (Thread/sleep 1000) {:status 500})
    (fn [base]
      (fact (error-message #(mcp/with-tools {:servers [(assoc server :url (str base "/mcp"))]
                                             :timeout-ms 200} identity))
            => "Could not connect to MCP server search. Check its connection and credentials."))))

(fact "tolerant HTTP discovery isolates failures and never calls tools"
  (let [requests (atom []) rpc (support/rpc-handler requests false)]
    (support/with-http-server
      (fn [request]
        (if (= "/offline" (:path request))
          {:status 401 :body {:error "private-server-details"}}
          (rpc request)))
      (fn [base]
        (let [config {:servers [(assoc server :url (str base "/mcp"))
                                {:name "offline" :url (str base "/offline")}]
                      :timeout-ms 1000 :tolerant? true}]
          (with-redefs [catalog/shared-cache
                        (catalog/new-cache (constantly 0) (fn [job] (job)))
                        catalog/start! (constantly nil)]
            (mcp/with-tools config
              (fn [tools]
                (fact (mapv :name tools) => ["web_search"]
                      (mapv :status (::mcp/statuses (meta tools)))
                      => ["fresh" "unavailable"]
                      (.contains (pr-str (meta tools))
                                 "private-server-details") => false)))
            (mcp/with-tools config
              #(fact (mapv :name %) => ["web_search"]))
            (fact
              (count (filter #(= "tools/list" (get-in % [:body :method]))
                             @requests)) => 2
              (some #(= "tools/call" (get-in % [:body :method]))
                    @requests) => nil
              (some #(= "DELETE" (:method %)) @requests) => true)))))))

(fact "the AI command can search a real local MCP server and then answer with its source link"
  (let [requests (atom []) rpc (support/rpc-handler requests false)]
    (support/with-http-server
      (fn [request]
        (if (= "/v1/chat/completions" (:path request))
          (do
            (swap! requests conj request)
            {:body {:choices [{:message
                               (if (some #(= "tool" (:role %)) (get-in request [:body :messages]))
                                 {:role "assistant" :content "Latest news: https://source.test/news"}
                                 {:role "assistant" :content nil
                                  :tool_calls [{:id "search-1" :type "function"
                                                :function {:name (get-in request [:body :tools 0 :function :name])
                                                           :arguments "{\"query\":\"today's news\"}"}}]})}]}})
          (rpc request)))
      (fn [base]
        (with-redefs [catalog/shared-cache
                      (catalog/new-cache (constantly 0) (fn [job] (job)))
                      catalog/start! (constantly nil)
                      ai/config (constantly {:providers [{:name "local" :url (str base "/v1")
                                                         :key "model-secret" :model "chat"}]})
                      mcp/config (constantly {:servers [(assoc server :url (str base "/mcp")
                                                                     :headers {"Authorization" "Bearer mcp-secret"})]})]
          (let [result (command/prompt-cmd {:match "What's new today?"})
                model-requests (filter #(= "/v1/chat/completions" (:path %)) @requests)
                mcp-requests (filter #(= "/mcp" (:path %)) @requests)]
            (fact
              (:result/value result) => "Latest news: https://source.test/news"
              (get-in result [:result/data :mcp :tool-calls]) => 1
              (count model-requests) => 2
              (every? #(= "Bearer model-secret" (get-in % [:headers "authorization"])) model-requests) => true
              (boolean (re-find #"mcp-secret" (json/generate-string (map :body model-requests)))) => false
              (every? #(= "Bearer mcp-secret" (get-in % [:headers "authorization"])) mcp-requests) => true
              (boolean (re-find #"https://source.test/news"
                                (-> model-requests last :body :messages last :content))) => true
              (some #(= "DELETE" (:method %)) mcp-requests) => true
              ;; Discovery closes before inference; the actual call reconnects
              ;; without repeating the fixture's two tools/list pages.
              (count (filter #(= "tools/list" (get-in % [:body :method]))
                             mcp-requests)) => 2
              (count (filter #(= "initialize" (get-in % [:body :method]))
                             mcp-requests)) => 2)))))))
