;; Run in a fresh JDK 17 process: HttpClient initialization order must not hide
;; its restricted-callback behavior once Clojail installs the SecurityManager.
(require '[yetibot.commands.clojure :as clojure-command])
(assert (= "3" (:result/value (clojure-command/clojure-cmd {:args "(+ 1 2)"}))))
(def installed-manager (System/getSecurityManager))
(assert installed-manager)

(require '[yetibot.api.mcp :as mcp]
         '[yetibot.test.support.mcp :as support])
(doseq [sse [false true]]
  (support/with-http-server
    (support/rpc-handler (atom []) sse)
    (fn [base]
      (let [session (atom nil)]
        (mcp/with-tools
          {:servers [{:name "sandbox-regression" :url (str base "/mcp")
                      :tools ["web_search"]}]
           :timeout-ms 5000}
          (fn [tools]
            (reset! session (:session (first tools)))
            (let [result (mcp/call-tool @session "web_search" {"query" "news"})]
              (assert (false? (:isError result)))
              (assert (= "https://source.test/news"
                         (get-in result [:structuredContent :results 0 :url]))))))
        (assert (.isDisposed (:scheduler @session)))))))

;; Failed initialization must release the same per-session worker too.
(support/with-http-server
  (fn [_] {:status 401 :body {:error "test-denial"}})
  (fn [base]
    (let [closed (atom nil)
          original-close mcp/close!]
      (with-redefs [mcp/close! (fn [resources]
                                (reset! closed resources)
                                (original-close resources))]
        (assert
          (try
            (mcp/with-tools
              {:servers [{:name "denied" :url (str base "/mcp")
                          :tools ["web_search"]}] :timeout-ms 5000}
              (fn [_] (throw (AssertionError. "Unauthorized session opened"))))
            false
            (catch clojure.lang.ExceptionInfo _ true))))
      (assert (:scheduler @closed))
      (assert (.isDisposed (:scheduler @closed))))))

(assert (identical? installed-manager (System/getSecurityManager)))
(assert
  (try
    (clojure-command/clojure-cmd {:args "(System/getenv \"MCP_REGRESSION_FORBIDDEN\")"})
    false
    (catch Throwable _ true)))
(assert
  (try
    (clojure-command/clojure-cmd {:args "(yetibot.api.mcp/config)"})
    false
    (catch Throwable _ true)))
(println "MCP_SECURITY_OK")
(shutdown-agents)
