(ns yetibot.test.integration.ai-autodraft
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as api] [yetibot.api.clef :as clef] [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai] [yetibot.models.ai-context :as context]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.test.integration.ai-tools :as fixture]
            [yetibot.test.support.ai-execution :as chat]
            [yetibot.test.support.mcp :as support]))

(fact "automatic routing uses real model/MCP transports and opens exactly one draft"
  (let [requests (atom []) pushed (atom false)
        staging-attempts (atom 0) stage! proposals/stage!]
    (support/with-http-server
      (fixture/fixture-handler requests pushed)
      (fn [base]
        (let [provider {:name "local" :url (str base "/v1") :model "chat"}
              t {:name "github-pr" :kind "github-pr" :users ["U1"] :adapters ["slack"]
                 :conversations ["workspace/C1"] :repositories ["owner/repo"]
                 :providers [{:name "local" :models ["chat"]}] :base-branches ["main"]
                 :allowed-paths ["src/"] :write-server "writer"
                 :servers [{:name "reader" :tools fixture/reader-tools}]}
              config {:default-toolset "github-pr" :toolsets [t]
                      :servers [{:name "reader" :url (str base "/read") :tools fixture/reader-tools
                                 :github-api-token "ancestry-secret"}
                                {:name "writer" :url (str base "/write") :tools fixture/writer-tools
                                 :headers {"Authorization" "Bearer write-secret"}}]}
              request (assoc-in chat/request [:chat-source :raw-event :ts] (str (java.util.UUID/randomUUID)))]
          (with-redefs [api/config (constantly {:providers [provider]
                                               :intent-router {:enabled true :github-toolset "github-pr"}})
                        clef/classify! (constantly :code-change)
                        clef/credential! (constantly "fixture-clef-secret")
                        mcp/config (constantly config)
                        context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                        client/get (fn [url _] {:status 200 :body {:sha (last (string/split url #"/"))
                                                                  :parents [{:sha fixture/base-sha}]}})]
            (let [result (chat/run-command request "ai Fix src/a.clj in owner/repo")]
              (fact (get-in result [:data :status]) => :succeeded
                    (get-in result [:data :pr-url]) => "https://github.com/owner/repo/pull/12"))
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
                    result (chat/run-command
                             r "ai Fix src/a.clj in owner/repo")
                    trace (get-in result [:data :tool-trace])
                    row (first (:calls trace))]
                (fact (:value result) => string?
                      (string/includes? (:value result)
                                        "Tool budget: total=6 used=1") => true
                      (contains? result :error) => false
                      (get-in result [:data :isError]) => true
                      (:reason trace) => :tool-exception
                      (:budget trace) => {:limit 6 :used 1 :remaining 5
                                          :requested 1}
                      (count (:calls trace)) => 1
                      (:tool row) => "mcp_5___stage_changes"
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
            (chat/run-command request "ai Fix src/a.clj in owner/repo")
            (fact (count (filter #(= "push_files" (get-in % [:body :params :name])) @requests)) => 1
                  (get-in (last (filter #(= "create_pull_request" (get-in % [:body :params :name])) @requests))
                          [:body :params :arguments :draft]) => true
                  (string/includes?
                    (get-in (first (filter #(= "create_branch" (get-in % [:body :params :name])) @requests))
                            [:body :params :arguments :branch]) "request-") => true)))))))

(fact "automatic route honors an explicit budget above the backend default"
  (let [requests (atom []) pushed (atom false)]
    (support/with-http-server
      (fixture/fixture-handler requests pushed)
      (fn [base]
        (let [provider {:name "local" :url (str base "/v1") :model "chat"}
              t {:name "github-pr" :kind "github-pr" :users ["U1"]
                 :adapters ["slack"] :conversations ["workspace/C1"]
                 :repositories ["owner/repo"] :base-branches ["main"]
                 :providers [{:name "local" :models ["chat"]}]
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
                                      :github-toolset "github-pr"}})
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
            (let [result (chat/run-command
                           request
                           (str "ai --max-tool-calls 12 "
                                "Fix src/a.clj in owner/repo"))
                  model-requests (filter #(= "/v1/chat/completions"
                                             (:path %)) @requests)
                  messages (get-in (first model-requests) [:body :messages])]
              (fact (get-in result [:data :status]) => :succeeded
                    (:max-tool-calls config) => nil
                    (:max-tool-calls t) => nil
                    (count model-requests) => 1
                    (some #(= (str "Host tool-call budget: total=12 "
                                   "used=0 remaining=12. Keep the entire "
                                   "next batch within remaining. When "
                                   "exhausted, stop fetching and answer "
                                   "with available evidence.")
                              (:content %)) messages) => true
                    (count (filter #(= "push_files"
                                       (get-in % [:body :params :name]))
                                   @requests)) => 1
                    (count (filter #(= "create_pull_request"
                                       (get-in % [:body :params :name]))
                                   @requests)) => 1))))))))
