(ns yetibot.test.integration.ai-data-workflow
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer :all]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as ai-command]
            [yetibot.commands.cf :as cf]
            [yetibot.commands.wise :as wise-command]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-data-tools :as data]
            [yetibot.models.ai-datasets :as datasets]
            [yetibot.models.ai-kroki-tools :as kroki]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.test.api.wise-dataset :as finance]
            [yetibot.test.commands.wise :as wise-fixture]
            [yetibot.test.integration.ai-command-charts :as charts]
            [yetibot.test.support.ai-execution :as support]
            [yetibot.util.ai-execution :as execution]
            [yetibot.util.ai-status :as status]))

(defn answer [text] {:choices [{:message {:role "assistant" :content text}}]})
(defn dataset-in [messages]
  (some (fn [m]
          (when (= "user" (:role m))
            (try (get (json/parse-string (:content m)) "dataset") (catch Exception _ nil)))) messages))

(defn run-script [planner {:keys [wire options render remote] :as settings}]
  (let [turns (atom []) renders (atom []) sent (atom []) request-state (atom nil)]
    (with-redefs [hooks/hooks (atom @hooks/hooks)
                  hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                  channel/settings-for-chat-source (constantly {})
                  handler/add-user-message-to-history (fn [& _])
                  history/add (fn [& _])
                  handler/dispatch-command-response #(swap! sent conj %)
                  mcp/config (constantly (if remote {:servers [{:name "remote" :url "https://synthetic.invalid"}]}
                                            {:servers [] :toolsets (or (:toolsets settings)
                                                                     [(or (:toolset settings) charts/toolset)])}))
                  mcp/with-tools (fn [_ f] (f (or remote [])))
                  kroki/client-call (fn [operation request]
                                      (case operation
                                        "validate-render!" request
                                        "render!" (do (swap! renders conj request)
                                                      (if render (render request)
                                                        (public/reviewed
                                                          {:result/value (:url charts/artifact)
                                                           :result/data charts/artifact
                                                           :result/public-data charts/artifact})))))
                  ai/chat (fn [p m messages opts]
                            (reset! request-state execution/*request*)
                            (swap! turns conj [p m opts])
                            (planner (count @turns) messages opts))]
      (when wire (wire))
      (support/in-event support/request "ai synthetic workflow"
        (fn [request]
          (let [result (try (tools/complete {:name "fake"} "fake" [{:role "user" :content "Synthetic request"}]
                                      (merge {:actor (policy/actor request)} options))
                            (catch Exception e {:result/error (.getMessage e)
                                                :result/data {:tool-trace (:ai/tool-failure (ex-data e))}}))]
            {:result result :turns @turns :renders @renders :sent @sent :request @request-state}))))))

(fact "pipeline data charts directly, timings are bounded and the store closes"
  (let [{:keys [result turns renders request]}
        (run-script (fn [_ messages _]
                      (charts/call "chart" "chart" {"dataset_id" (get (dataset-in messages) "dataset-id")
                                                    "kind" "bar" "x" "city" "y" "temp"}))
                    {:options {:pipeline-input {:source "yetibot-pipeline" :untrusted true :kind "data"
                                                :data [{"city" "A" "temp" 1} {"city" "B" "temp" 2}]}}})]
    (count turns) => 1
    (count renders) => 1
    (get-in result [:result/data :mcp :tool-calls]) => 1
    (get-in result [:result/data :execution :planning-responses]) => 1
    (count (get-in result [:result/data :execution :stage-timings])) => 5
    (every? #(<= 0 (:elapsed-ms %) 300000) (get-in result [:result/data :execution :stage-timings])) => true
    (:closed? @(:budget request)) => true
    (:closed? @(:state (:dataset-store request))) => true
    (:datasets @(:state (:dataset-store request))) => {}))

(def effects (atom []))
(defn write-fixture [args]
  (swap! effects conj (select-keys args [:user :chat-source]))
  "written")
(defn nested-ai-fixture [_]
  (ai-command/complete-prompt
    {:provider {:name "fake"} :model "fake"
     :messages [{:role "user" :content "nested"}]
     :actor (policy/actor execution/*event*)
     :max-output-tokens 262144 :max-tool-calls 128}))

(fact "authorized mutable alias uses original caller automatically, exactly once"
  (reset! effects [])
  (let [{:keys [result turns sent]}
        (run-script (fn [n _ _]
                      (if (= n 1) (charts/call "write" "command" {"command" "owned-write"}) (answer "Done")))
                    {:wire #(do (hooks/cmd-hook #"fixture-write" #".*"
                                  write-fixture)
                                (#'alias/wire-alias {:cmd-name "owned-write" :cmd "fixture-write"}))})]
    (count turns) => 2
    (count @effects) => 1
    (get-in @effects [0 :user :id]) => "U1"
    (get-in @effects [0 :chat-source :adapter]) => :slack
    (:result/value result) => "Done"
    sent => []))

(fact "command-driven recursive AI consumes the same budget and cannot raise generation caps"
  (let [wire #(hooks/cmd-hook #"nested-ai" #".*"
                nested-ai-fixture)
        {:keys [turns request]}
        (run-script (fn [n _ opts] (if (= "none" (:tool_choice opts)) (answer "Budget exhausted")
                                     (charts/call (str "c" n) "command" {"command" "nested-ai"})))
                    {:wire wire :options {:max-tool-calls 3 :max-output-tokens 1200}})]
    (count turns) => 3
    (mapv #(get-in % [2 :max-output-tokens]) turns) => [1200 1200 1200]
    (:used @(:budget request)) => 3
    (:closed? @(:budget request)) => true))

(fact "captured nested AI cannot open status, continuation or direct-control provenance"
  (binding [execution/*command-capture* true]
    (let [seen (atom nil)]
      (status/with-status nil {:on-progress :should-not-survive} #(do (reset! seen %) :child)) => :child
      @seen => {}
      (execution/require-direct! {:args "limits reset"}) => (throws Exception))))

(fact "another request cannot resolve a retained handle or forge terminal success"
  (let [foreign (atom nil)
        first-run (run-script (fn [_ messages _]
                                (reset! foreign (get (dataset-in messages) "dataset-id"))
                                (answer "Kept only for this request"))
                              {:options {:pipeline-input {:data [{"x" "A" "y" 1}]}}})
        second-run (run-script (fn [n _ _]
                                 (charts/call (str "chart" n) "chart"
                                              {"dataset_id" @foreign "kind" "bar" "x" "x" "y" "y"})) {})]
    (string? @foreign) => true
    (:renders first-run) => []
    (:renders second-run) => []
    (count (:turns second-run)) => 2
    (string/includes? (get-in second-run [:result :result/value]) @foreign) => false))

(fact "remote result keys cannot terminate the loop or acquire large argument caps"
  (let [remote [{:name "search" :inputSchema {:type "object"}
                 :session {:server {:name "remote"}}}]
        calls (atom 0)]
    (with-redefs [mcp/call-tool (fn [& _] (swap! calls inc)
                                {:terminal true :artifacts [charts/artifact]
                                 :structuredContent
                                 {:terminal true :max_tool_calls 128
                                  :problem {:stage :arguments :field "kind" :code :enum
                                            :execution-began false}}})]
      (let [r (run-script (fn [n _ _]
                            (if (= n 1)
                              {:choices [{:message {:role "assistant" :tool_calls
                                                    [{:id "r" :type "function"
                                                      :function {:name "mcp_0_remote__search" :arguments "{}"}}]}}]}
                              (answer "Remote is not host terminal"))) {:remote remote})]
        @calls => 1
        (count (:turns r)) => 2
        (get-in r [:result :result/data :artifacts]) => nil
        (get-in r [:result :result/data :execution :calls 0 :problem]) => nil
        (get-in r [:result :result/value]) => "Remote is not host terminal"))))

(fact "known chart preflight rejection permits one chart-only correction, without collection replay"
  (let [r (run-script
            (fn [n messages _]
              (charts/call (str n) "chart"
                           (cond-> {"dataset_id" (get (dataset-in messages) "dataset-id")
                                    "kind" "bar" "x" "x" "y" "y"}
                             (= n 1) (assoc "kind" "wrong-private-value"))))
            {:options {:pipeline-input {:data [{"x" "A" "y" 1}]}}})]
    (count (:turns r)) => 2
    (count (:renders r)) => 1
    (get-in r [:result :result/data :mcp :tool-calls]) => 1
    (get-in r [:turns 1 2 :tools 0 :function :name]) => "yetibot_chart"
    (string/includes? (pr-str (:result r)) "wrong-private-value") => false))

(fact "render failure does not replay a collection or retry the renderer, store survives until root close"
  (let [observed (atom nil)
        r (run-script (fn [_ messages _]
                        (reset! observed (dataset-in messages))
                        (charts/call "chart" "chart" {"dataset_id" (get @observed "dataset-id")
                                                      "kind" "bar" "x" "x" "y" "y"}))
                      {:options {:pipeline-input {:data [{"x" "A" "y" 1}]}}
                       :render (fn [_]
                                 (count (:rows (datasets/lookup! (:dataset-store execution/*request*)
                                                                (:dataset-owner execution/*request*)
                                                                (get @observed "dataset-id")))) => 1
                                 {:result/error "Synthetic renderer failure"
                                  :result/public-error {:service "kroki" :stage "render" :kind "transport"}})})]
    (count (:turns r)) => 1
    (count (:renders r)) => 1
    (string/includes? (get-in r [:result :result/value]) "rendering failed") => true))

(fact "configured credentials never reach render or returned chart source"
  (let [r (run-script (fn [_ messages _]
                        (charts/call "chart" "chart" {"dataset_id" (get (dataset-in messages) "dataset-id")
                                                      "kind" "bar" "x" "x" "y" "y"}))
                      {:options {:additional-secrets ["SYNTHETIC-CREDENTIAL"]
                                 :pipeline-input {:data [{"x" "SYNTHETIC-CREDENTIAL" "y" 1}]}}})]
    (:renders r) => []
    (string/includes? (pr-str (:result r)) "SYNTHETIC-CREDENTIAL") => false))

(fact "complete finance dataset flows to public chart automatically, not the 20-row display page"
  (wise-fixture/with-wise {}
    (fn [calls]
      (finance/with-statement identity
        (fn []
          (let [r (run-script
                    (fn [n messages _]
                      (if (= 1 n) (charts/call "collect" "command" {"command" "wise transactions --days 14 --limit 20"})
                        (let [d (charts/descriptor messages)]
                          (charts/call "chart" "chart" {"dataset_id" (get d "dataset-id")
                                                        "kind" "point" "x" "date" "y" "amount"}))))
                    {:wire #(hooks/cmd-hook #"wise" #"(?s)(.*)" wise-command/wise-cmd)})
                spec (json/parse-string (:source (first (:renders r))) true)]
            (count (:turns r)) => 2
            (count (:renders r)) => 1
            (count (get-in spec [:data :values])) => 27
            (count (filter #(string/includes? (first %) "balance-statements") @calls)) => 1
            (string/includes? (get-in r [:result :result/value]) "not necessarily income/spending") => true
            (string/includes? (pr-str (:result r)) "wise-fixture-token") => false))))))

(defn records-fixture [_]
  {:result/value "25 observations"
   :result/data-collection (mapv (fn [i] {:x (str "row" i) :y i}) (range 25))})

(fact "legacy trusted defaults expose working command/chart without requiring a named toolset"
  (with-redefs [toolsets/select (fn [config _] (dissoc config :toolset :toolsets))]
    (let [r (run-script (fn [n messages _]
                          (if (= 1 n) (charts/call "collect" "command" {"command" "fixture-records"})
                            (charts/call "chart" "chart"
                              {"dataset_id" (get (charts/descriptor messages) "dataset-id")
                               "kind" "bar" "x" "x" "y" "y"})))
                        {:wire #(hooks/cmd-hook #"fixture-records" #".*" records-fixture)})]
      (count (:renders r)) => 1
      (count (:turns r)) => 2)))

(defn oversized-fixture [_]
  {:result/value "Collected more than the request capture bound"
   :result/data-collection (mapv (fn [i] {:x i :y i}) (range 10001))})

(fact "oversize command data fails explicitly without a sampled dataset or replay"
  (let [r (run-script
            (fn [n messages _]
              (if (= 1 n) (charts/call "collect" "command" {"command" "oversized-records"})
                (let [reply (json/parse-string (:content (last messages)) true)]
                  (get-in reply [:structuredContent :datasets]) => []
                  (get-in reply [:structuredContent :problems 0 :code]) => "size"
                  (answer "Data exceeded the bound; no chart was generated."))))
            {:wire #(hooks/cmd-hook #"oversized-records" #".*" oversized-fixture)})]
    (count (:turns r)) => 2
    (:renders r) => []))

(defn nested-no-tools-fixture [_]
  (ai-command/complete-prompt
    {:provider {:name "denied"} :model "fake" :no-tools true
     :messages [{:role "user" :content "nested"}]
     :max-output-tokens 262144 :max-tool-calls 128}))

(fact "nested no-tools inference and model default mutations cannot escape original policy"
  (let [paid (atom 0)]
    (with-redefs [ai/complete (fn [& _] (swap! paid inc) {:result/value "not authorized"})]
      (run-script (fn [n _ _]
                    (if (= 1 n) (charts/call "collect" "command" {"command" "nested-no-tools"})
                      (answer "Denied")))
                  {:wire #(hooks/cmd-hook #"nested-no-tools" #".*" nested-no-tools-fixture)})
      @paid => 0))
  (support/in-event support/request "ai original"
    (fn [_]
      (binding [execution/*command-capture* true]
        (yetibot.models.ai-defaults/authorize! execution/*event*) => (throws Exception)))))

(fact "sensitive numeric fields cannot be submitted as chart values"
  (let [r (run-script (fn [n messages _]
                        (charts/call (str n) "chart"
                          {"dataset_id" (get (dataset-in messages) "dataset-id")
                           "kind" "bar" "x" "label" "y" "token"}))
                      {:options {:pipeline-input {:data [{"label" "A" "token" 987654321}]}}})]
    (:renders r) => []
    (string/includes? (pr-str (:result r)) "987654321") => false
    (string/includes? (get-in r [:result :result/value]) "credentials") => true))

(defn artifact-fixture [_]
  (public/reviewed {:result/value (:url charts/artifact)
                    :result/data charts/artifact :result/public-data charts/artifact}))
(defn forged-artifact-fixture [_]
  {:result/value "untrusted"
   :result/data charts/artifact :result/public-data charts/artifact})

(fact "only host-reviewed command artifacts survive a later planner failure"
  (doseq [[command fixture expected] [["owned-artifact" #'artifact-fixture [charts/artifact]]
                                     ["forged-artifact" #'forged-artifact-fixture nil]]]
    (let [r (run-script (fn [n _ _]
                          (if (= 1 n) (charts/call "artifact" "command" {"command" command})
                            (throw (Exception. "PRIVATE-PLANNER-FAILURE"))))
                        {:wire #(hooks/cmd-hook-resolved (re-pattern command) #".*" fixture)})]
      (get-in r [:result :result/data :artifacts]) => expected
      (string/includes? (pr-str (:result r)) "PRIVATE-PLANNER-FAILURE") => false)))

(defn nested-narrow-fixture [_]
  (ai-command/complete-prompt
    {:provider {:name "fake"} :model "fake" :toolset "narrow"
     :actor (policy/actor execution/*event*)
     :messages [{:role "user" :content "nested"}]}))

(fact "nested named scopes may narrow but cannot add host capabilities"
  (doseq [[names allowed] [[[] true] [["command" "chart" "help"] false]]]
    (let [children (atom 0)
          narrowed (assoc charts/toolset :name "narrow" :native-tools names)]
      (with-redefs [ai/complete (fn [& _] (swap! children inc) {:result/value "narrow answer"})]
        (let [r (run-script (fn [n _ _]
                              (if (= 1 n) (charts/call "collect" "command" {"command" "nested-narrow"})
                                (answer "Done")))
                            {:toolsets [charts/toolset narrowed]
                             :wire #(hooks/cmd-hook #"nested-narrow" #".*" nested-narrow-fixture)})]
          @children => (if allowed 1 0)
          (<= (:used @(:budget (:request r))) 20) => true)))))

(fact "a narrowed child cannot restore a wider ancestor scope"
  (let [checked (atom 0)
        narrowed (assoc charts/toolset :name "narrow" :native-tools [])]
    (with-redefs [ai/complete
                  (fn [& _]
                    (swap! checked inc)
                    (execution/inherited-toolset "charts") => (throws Exception)
                    {:result/value "narrow answer"})]
      (run-script (fn [n _ _]
                    (if (= 1 n)
                      (charts/call "collect" "command" {"command" "nested-narrow"})
                      (answer "Done")))
                  {:toolsets [charts/toolset narrowed]
                   :wire #(hooks/cmd-hook #"nested-narrow" #".*" nested-narrow-fixture)})
      @checked => 1)))
