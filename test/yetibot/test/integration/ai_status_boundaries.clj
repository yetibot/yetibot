(ns yetibot.test.integration.ai-status-boundaries
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.clef :as clef]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.webapp.routes.images :as images]
            [yetibot.models.ai-github :as github]
            [yetibot.models.ai-model-tools :as children]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.ai-status :as proof]
            [yetibot.util.ai-execution :as execution]))

(defn message [text]
  {:type "message" :role "assistant" :status "completed"
   :content [{:type "output_text" :text text}]})

(defn response [output]
  {:status 200 :headers {"content-type" "application/json"}
   :body (java.io.ByteArrayInputStream.
           (.getBytes (json/generate-string
                        {:status "completed" :output output}) "UTF-8"))})

(fact "expected-card wait selects a full existing card and removes its watch"
  (let [expected "Working\n#1 sync-fixture — error"
        card (fn [text]
               {:lane :status :body {:flags 32768
                       :components [{:components [{:content text}]}]}})
        intermediate (card (str expected "\nstill intermediate"))
        matching (assoc (card expected) :method :post)
        calls (atom [intermediate matching])]
    (proof/await-card calls expected) => matching
    (.getWatches calls) => {}))

(fact "actual host/native accounting reaches Grok before later tool completion"
  (let [complete tools/complete post http/post call mcp/call-tool
        config mcp/config with-tools mcp/with-tools
        running (promise) release-tool (promise) done (promise)
        release-final (promise) turns (atom [])
        server {:name "status-search" :url "http://status-fixture.invalid/mcp"}
        tool {:name "lookup" :inputSchema {:type "object"}
              :session {:server server}}]
    (proof/scenario
      {:generate
       (fn [p model messages options]
         (let [result (complete (assoc p :api "responses"
                                        :native-tools ["web_search"])
                                model messages options)]
           (deliver done true)
           (proof/await release-final)
           result))}
      (fn [{:keys [run! created edited answered calls deleted]}]
        (with-redefs
          [mcp/config (fn [] (if proof/*fixture*
                              {:servers [server] :max-tool-calls 7}
                              (config)))
           mcp/with-tools
           (fn [settings f]
             (if (= server (first (:servers settings))) (f [tool])
               (with-tools settings f)))
           mcp/call-tool
           (fn [session name args]
             (if (and (= (assoc server :tools ["lookup"]) (:server session))
                      (= "lookup" name) (= {} args))
               (do
                 (deliver running true)
                 (proof/await release-tool)
                 {:isError true
                  :content [{:type "text" :text proof/forbidden}]})
               (call session name args)))
           http/post
           (fn [url options]
             (if-not (= url "http://status-fixture.invalid/v1/responses")
               (post url options)
               (let [body (:form-params options)
                     n (count (swap! turns conj body))
                     name (some #(when (= "function" (:type %)) (:name %))
                                (:tools body))]
                 (response
                   (if (= n 1)
                     [{:id "PRIVATE-NATIVE" :type "web_search_call"
                       :status "completed" :action {:query proof/forbidden}}
                      (message "Immediate")
                      {:type "function_call" :call_id "PRIVATE-CALL"
                       :name name :arguments "{}"}]
                     [{:id "PRIVATE-NATIVE" :type "web_search_call"
                       :status "failed" :action {:query proof/forbidden}}
                      (message "Final")])))))]
          (let [completion (run! "grok --no-thread fixture")]
            (try
              (fact (proof/await running) => true
                    (get-in (proof/await answered) [:body :components 0 :components 0 :content])
                    => (str "Immediate\n\n-# " proof/model " · cost n/a")
                    (not= :timeout (proof/await created)) => true)
              (fact (empty? (proof/deletions @calls)) => true)
              (deliver release-tool true)
              (fact (proof/await done) => true)
              (proof/await-card
                calls (str "Tools: 1/7 · 6 remaining\n"
                           "Search 1 #1 web search — success\n"
                           "#1 mcp_0_status-search__lookup — error\n"
                           "Search 2 #1 web search — error"))
              (let [card (last (proof/status-calls @calls))]
                (fact (proof/status-text card)
                      => (str "Tools: 1/7 · 6 remaining\n"
                              "Search 1 #1 web search — success\n"
                              "#1 mcp_0_status-search__lookup — error\n"
                              "Search 2 #1 web search — error")
                      (boolean (re-find #"PRIVATE|RAW-|SECRET"
                                        (str (:body card)))) => false))
              (finally
                (deliver release-tool true)
                (deliver release-final true)))
            (proof/finish! completion)
            (fact (not= :timeout (proof/await deleted)) => true
                  (count @turns) => 2
                  (some #(contains? % :on-progress) @turns) => nil
                  (get-in (last (proof/answer-calls @calls)) [:body :components 0 :components 0 :content])
                  => (str "Final\n\n-# " proof/model " · cost n/a"))))))))

(fact "no-tools keeps a tool-free Working card and ordinary final transport"
  (let [post http/post release (promise) wire (atom []) discovery (atom 0)
        original-discovery mcp/with-tools]
    (proof/scenario
      {:generate (fn [& _] (throw (Exception. "Unexpected tool execution")))}
      (fn [{:keys [run! calls created deleted]}]
        (let [config ai/config]
          (with-redefs
            [ai/config
             (fn []
               (if proof/*fixture*
                 (update (config) :providers
                         #(mapv (fn [p] (assoc p :api "responses")) %))
                 (config)))
             http/post
             (fn [url options]
               (if-not (= url "http://status-fixture.invalid/v1/responses")
                 (post url options)
                 (do
                   (swap! wire conj (:form-params options))
                   (proof/await release)
                   (response [(message "No tools answer")]))))
             mcp/with-tools
             (fn [settings f]
               (if proof/*fixture* (swap! discovery inc)
                 (original-discovery settings f)))]
            (let [completion (run! "ai --no-tools fixture")]
              (try
                (fact (not= :timeout (proof/await created)) => true
                      (proof/status-text
                        (first (proof/status-calls @calls))) => "Working")
                (finally (deliver release true)))
              (proof/finish! completion)
              (fact (not= :timeout (proof/await deleted)) => true
                    @discovery => 0
                    (some #(seq (:tools %)) @wire) => nil
                    (some #(contains? % :on-progress) @wire) => nil
                    (get-in (last (proof/answer-calls @calls)) [:body :components 0 :components 0 :content])
                    => #(and (proof/captioned? % "No tools answer")
                             (string/ends-with? % " · 0 tools · cost n/a"))))))))))

(fact "missing ancillary lane or failed status create cannot discard answers"
  (doseq [failure [:missing :failed]]
    (let [release (promise)]
      (proof/scenario
        {:missing-lane? (= failure :missing)
         :status-failure? (= failure :failed)
         :generate (fn [& _]
                     (when (= failure :failed) (proof/await release))
                     {:result/value "Answer survives"})}
        (fn [{:keys [run! calls created]}]
          (let [completion (run! "ai fixture")]
            (try
              (when (= failure :failed)
                (fact (not= :timeout (proof/await created)) => true))
              (finally (deliver release true)))
            (proof/finish! completion)
            (fact (get-in (last (proof/answer-calls @calls)) [:body :components 0 :components 0 :content])
                  => #(proof/captioned? % "Answer survives")
                  (proof/deletions @calls) => [])
            (when (= failure :missing)
              (fact (proof/status-calls @calls) => []))))))))

(fact "final generated-image attachment is explicit V2 media separate from working card"
  (let [release (promise)
        id "status-fixture-image"
        url (str "https://status-fixture.invalid/generated-images/" id ".png")]
    (with-redefs [images/image-store
                  (atom {id {:data "aW1hZ2U="}})]
      (proof/scenario
        {:generate (fn [& _]
                     (proof/await release)
                     {:result/value (str "Caption " url)})}
        (fn [{:keys [run! calls created deleted]}]
          (let [completion (run! "ai fixture")]
            (try
              (fact (not= :timeout (proof/await created)) => true)
              (finally (deliver release true)))
            (proof/finish! completion)
            (fact (not= :timeout (proof/await deleted)) => true)
            (let [answer (first (proof/answer-calls @calls))
                  card (first (proof/status-calls @calls))]
              (fact (:file? answer) => true
                    (get-in answer [:body :components 0 :components 0 :content])
                    => #(proof/captioned? % "Caption ")
                    (get-in answer [:body :flags]) => 32768
                    (:file? card) => false
                    (mapv :id (proof/deletions @calls)) => [(:id card)]))))))))

(fact "autodraft forwards only parent progress to actual tools not classifier"
  (let [classifier-release (promise) generated (promise) release (promise)
        classified (atom []) writes (atom []) options-seen (atom [])
        t {:name "status-pr" :kind "github-pr"
           :users ["status-fixture-user"] :adapters ["discord"]
           :repositories ["owner/repo"] :base-branches ["main"]
           :allowed-paths ["src/"] :write-server "writer"
           :servers [{:name "status-reader"
                      :tools ["get_commit" "get_file_contents"]}]
           :providers [{:name "grok" :models [proof/model]}]}
        router {:enabled true :github-toolset "status-pr"}]
    (proof/scenario
      {:generate
       (fn [p model _ options]
         (swap! options-seen conj options)
         (when-let [notify (:on-progress options)]
           (notify {:tool-budget {:used 1 :limit 7 :remaining 6}
                    :tool-event {:domain :host :call 1 :label "stage_changes"
                                 :outcome :success}}))
         (let [proposal
               (proposals/stage!
                 t (:actor options) p model
                 {"owner" "owner" "repo" "repo" "base" "main"
                  "title" "Fix" "body" "Fix"
                  "files" [{"path" "src/a.clj" "content" "new\n"}]}
                 (fn [& _]
                   {:base-sha (apply str (repeat 40 "a"))
                    :originals {"src/a.clj" "old\n"}}))]
           (deliver generated true)
           (proof/await release)
           {:result/data {:proposal (:id proposal)}}))}
      (fn [{:keys [run! calls created edited deleted]}]
        (let [config ai/config mcp-config mcp/config
              credential clef/credential! classify clef/classify!
              execute github/execute!]
          (with-redefs
            [ai/config (fn [] (if proof/*fixture*
                               (assoc (config) :intent-router router)
                               (config)))
             mcp/config (fn [] (if proof/*fixture*
                                {:toolsets [t]
                                 :servers
                                 [{:name "status-reader"
                                   :tools ["get_commit" "get_file_contents"]
                                   :github-api-token "synthetic-status-reader"}
                                  {:name "writer"
                                   :tools ["create_branch" "push_files"
                                           "create_pull_request"]}]}
                                (mcp-config)))
             clef/credential! (fn [settings]
                                (if (= settings router)
                                  "PRIVATE-CLASSIFIER-SECRET"
                                  (credential settings)))
             clef/classify!
             (fn [settings text]
               (if-not (= settings router) (classify settings text)
                 (do
                   (swap! classified conj text)
                   (proof/await classifier-release)
                   :code-change)))
             github/execute!
             (fn [settings proposal]
               (if-not (= t (:toolset proposal)) (execute settings proposal)
                 (do
                   (swap! writes conj proposal)
                   {:status :unknown :branch "known-branch"})))]
            (let [completion (run! "ai --max-tool-calls 7 Fix owner/repo")]
              (try
                (fact (not= :timeout (proof/await created)) => true
                      (proof/status-text
                        (first (proof/status-calls @calls))) => "Working"
                      @options-seen => [])
                (deliver classifier-release true)
                (fact (proof/await generated) => true)
                (proof/await edited)
                (fact (proof/status-text (last (proof/status-calls @calls)))
                      => (str "Tools: 1/7 · 6 remaining\n"
                              "#1 stage_changes — success")
                      (fn? (:on-progress (first @options-seen))) => true
                      (:max-tool-calls (first @options-seen)) => 7
                      (:additional-secrets (first @options-seen))
                      => ["PRIVATE-CLASSIFIER-SECRET"])
                (finally
                  (deliver classifier-release true)
                  (deliver release true)))
              (proof/finish! completion)
              (fact (not= :timeout (proof/await deleted)) => true
                    @classified => ["--max-tool-calls 7 Fix owner/repo"]
                    (count @writes) => 1
                    (:execution (first @writes)) => :automatic
                    (boolean
                      (re-find #"Automatic PR stopped: unknown"
                               (get-in (last (proof/answer-calls @calls))
                                       [:body :components 0 :components 0 :content]))) => true)
              (proof/finish!
                (run! "ai --max-tool-calls 7 Fix owner/repo"))
              (fact (count @classified) => 1
                    (count @options-seen) => 1
                    (count @writes) => 1))))))))

(fact "delegated text child never inherits parent progress context or tools"
  (let [child-options (atom []) release (promise)]
    (proof/scenario
      {:generate
       (fn [_ _ _ options]
         (fact (fn? (:on-progress options)) => true)
         (let [result (children/call!
                        {} "ai"
                        {"action" "ask" "prompt" "Only supplied child text"}
                        (:chat-source execution/*event*))]
           (proof/await release)
           {:result/value (get-in result [:structuredContent :text])}))}
      (fn [{:keys [run! calls created deleted]}]
        (let [complete ai/complete]
          (with-redefs
            [ai/complete
             (fn [p model messages options]
               (if (= model proof/model)
                 (do (swap! child-options conj [p messages options])
                     {:result/value "Child answer"})
                 (complete p model messages options)))]
            (let [completion (run! "ai fixture")]
              (try
                (fact (not= :timeout (proof/await created)) => true)
                (finally (deliver release true)))
              (proof/finish! completion)
              (fact (not= :timeout (proof/await deleted)) => true
                    (count @child-options) => 1
                    (get-in @child-options [0 1])
                    => [{:role "user" :content "Only supplied child text"}]
                    (get-in @child-options [0 2 :on-progress]) => nil
                    (get-in @child-options [0 2 :progress-config]) => nil
                    (get-in @child-options [0 2 :tools]) => []
                    (get-in (last (proof/answer-calls @calls)) [:body :components 0 :components 0 :content])
                    => #(proof/captioned? % "Child answer")))))))))

(fact "stale callback cannot change the new command card or final answer"
  (let [turn (atom 0) callbacks (atom []) second-ready (promise)
        first-release (promise) second-release (promise) second-card (promise)]
    (proof/scenario
      {:generate
       (fn [_ _ _ options]
         (let [n (swap! turn inc)]
           (swap! callbacks conj (:on-progress options))
           (when (= n 2) (deliver second-ready true))
           (proof/await (if (= n 1) first-release second-release))
           {:result/value (if (= n 1) "Obsolete answer" "Current answer")}))}
      (fn [{:keys [run! calls created]}]
        (let [first-completion (run! "ai first")]
          (fact (not= :timeout (proof/await created)) => true)
          (add-watch calls ::second-card
                     (fn [_ _ _ records]
                       (let [cards (proof/status-calls records)
                             creates (filter #(= :post (:method %)) cards)
                             latest (last cards)]
                         (when (and (= 2 (count creates))
                                    (string/includes?
                                      (or (proof/status-text latest) "")
                                      "current_tool"))
                           (deliver second-card latest)))))
          (let [second-completion (run! "ai second")]
            (try
              (fact (proof/await second-ready) => true)
              ((first @callbacks)
               {:tool-event {:domain :host :call 1 :label "stale_tool"
                             :outcome :running}})
              ((second @callbacks)
               {:tool-event {:domain :host :call 1 :label "current_tool"
                             :outcome :error}})
              (fact (proof/status-text (proof/await second-card))
                    => "Working\n#1 current_tool — error")
              (finally
                (deliver first-release true)
                (deliver second-release true)))
            (proof/finish! first-completion)
            (proof/finish! second-completion)
            (fact (mapv #(get-in % [:body :components 0 :components 0 :content])
                        (proof/answer-calls @calls))
                  => #(and (= 1 (count %))
                           (proof/captioned? (first %) "Current answer"))
                  (some #(string/includes? (or (proof/status-text %) "")
                                           "stale_tool") @calls) => nil)
            (remove-watch calls ::second-card)))))))
