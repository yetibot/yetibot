(ns yetibot.test.integration.ai-data-workflow
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer :all]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as ai-command]
            [yetibot.commands.cf :as cf]
            [yetibot.commands.zen :as zen]
            [yetibot.commands.openai :as openai]
            [yetibot.commands.system-one :as system-one]
            [yetibot.commands.weather :as weather]
            [yetibot.api.clef :as clef]
            [yetibot.api.cloudflare :as cloudflare]
            [yetibot.api.cloudflare-images :as images]
            [yetibot.core.config :as config]
            [clj-http.client :as http]
            [yetibot.commands.wise :as wise-command]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-data-tools :as data]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-datasets :as datasets]
            [yetibot.models.ai-kroki-tools :as kroki]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.test.api.wise-dataset :as finance]
            [yetibot.test.commands.wise :as wise-fixture]
            [yetibot.test.integration.ai-command-charts :as charts]
            [yetibot.test.integration.ai-command-capture :as capture]
            [yetibot.test.support.ai-execution :as support]
            [yetibot.util.ai-execution :as execution]
            [yetibot.util.ai-status :as status]))

(defn answer [text] {:choices [{:message {:role "assistant" :content text}}]})
(defn dataset-in [messages]
  (some (fn [m]
          (when (= "user" (:role m))
            (try (get (json/parse-string (:content m)) "dataset") (catch Exception _ nil)))) messages))

(defn run-script [planner {:keys [wire options render remote] :as settings}]
  (let [turns (atom []) renders (atom []) sent (atom []) request-state (atom nil)
        child-post http/post
        configured (try (ai/config) (catch Exception _ {}))
        parent {:name "fake" :model "fake" :url "https://planner.invalid/v1"
                :key "SYNTHETIC-CREDENTIAL"}]
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
                  ai/config (constantly (update configured :providers
                                          #(into [parent] (remove (fn [p] (= "fake" (:name p))) %))))
                  http/post (fn [endpoint opts]
                              (if (= endpoint "https://planner.invalid/v1/chat/completions")
                                (let [form (:form-params opts)
                                      options (assoc form :max-output-tokens (:max_completion_tokens form))]
                                  (reset! request-state execution/*request*)
                                  (swap! turns conj [parent (:model form) options])
                                  {:status 200 :body (planner (count @turns) (:messages form) options)})
                                (child-post endpoint opts)))]
      (when wire (wire))
      (charts/with-current-caller
       #(support/in-event support/request "ai synthetic workflow"
        (fn [request]
          (let [result (ai-command/safely
                         (fn [] (tools/complete parent "fake" [{:role "user" :content "Synthetic request"}]
                                          (merge {:actor (policy/actor request)} options))))]
            {:result result :turns @turns :renders @renders :sent @sent :request @request-state})))))))

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
    {:provider {:name "fake" :url "https://planner.invalid/v1"} :model "fake"
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
    (count turns) => 2
    (mapv #(get-in % [2 :max-output-tokens]) turns) => [1200 1200]
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
    (count (:turns r)) => 1
    (get-in r [:result :result/data :execution :calls 0 :problem])
    => {:stage :arguments :field "*" :code :credential-input
        :remedy "Remove credentials from the request." :execution-began false}
    (string/includes? (get-in r [:result :result/value]) "execution did not begin") => true
    (string/includes? (str (get-in r [:result :result/value])) "Remove credentials") => true
    (string/includes? (pr-str (get-in r [:result :result/public-error])) "credential-input") => true
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
    {:provider {:name "fake" :url "https://planner.invalid/v1"} :model "fake" :toolset "narrow"
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

(fact "namespaced keyword and string credentials stay out of model previews and public charts"
  (doseq [key [:auth/token "auth/token"]]
    (let [r (run-script
              (fn [n messages _]
                (string/includes? (pr-str messages) "987654321") => false
                (get-in (dataset-in messages) ["preview" 0 "auth/token"]) => "[redacted]"
                (charts/call (str n) "chart"
                  {"dataset_id" (get (dataset-in messages) "dataset-id")
                   "kind" "bar" "x" "label" "y" "auth/token"}))
              {:options {:pipeline-input {:data [{"label" "A" key 987654321}]}}})]
      (:renders r) => []
      (string/includes? (pr-str (:result r)) "987654321") => false
      (get-in r [:result :result/data :execution :calls 0 :problem :code]) => :credential-input
      (string/includes? (str (get-in r [:result :result/value])) "Remove credentials") => true)))

(def zen-provider {:name "zen" :url "https://opencode.ai/inference/openai/v1"
                   :key "synthetic-zen-key" :model "paid"})
(def original-get-config config/get-config)
(def inference-policies
  (assoc charts/toolset :providers [{:name "fake" :models ["fake"]}
                                    {:name "zen" :models ["paid"]}
                                    {:name "openai" :models ["gpt-4"]}
                                    {:name "cloudflare" :models ["@cf/cloudflare/clef" "@cf/cloudflare/clairic"]}]))
(defn inference-wire []
  (hooks/cmd-hook #"zen" #"(?s)^.+$" zen/prompt-cmd)
  (hooks/cmd-hook #"openai" #"(?s)^.+$" openai/openai-cmd)
  (hooks/cmd-hook #"clef" #"(?s)(.*)" system-one/clef-cmd)
  (hooks/cmd-hook #"clairic" #"(?s)(.*)" system-one/clairic-cmd))

(defn command-plan [command]
  (fn [n _ _] (if (= n 1) (charts/call "collect" "command" {"command" command}) (answer "Done"))))

(fact "actual xargs inference commands cannot bypass exhausted budgets, denied providers or wider scopes"
  (doseq [command ["list A B | xargs zen --model paid"
                   "list A B | xargs openai"
                   "list A B | xargs clef Is it A?"
                   "list A B | xargs clairic demo"]
          [t limit] [[inference-policies 1] [charts/toolset 20]
                     [(update inference-policies :providers
                              #(mapv (fn [p] (if (= "fake" (:name p)) p
                                               (assoc p :models ["denied-model"]))) %)) 20]]]
    (let [sends (atom [])]
      (with-redefs [ai/config (constantly {:providers [zen-provider]})
                    ai/prepare-provider (fn [p _] p)
                    context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                    ai/complete (fn [& args] (swap! sends conj args) {:result/value "child"})
                    http/post (fn [& args] (swap! sends conj args) {:body {:choices [{:message {:content "child"}}]}})
                    config/get-config (fn [spec path]
                                        (if (= path [:ai])
                                          {:value {:intent-router {:account-id (apply str (repeat 32 "a"))}}}
                                          (original-get-config spec path)))
                    clef/credential! (constantly "synthetic-structured-key")
                    images/images (constantly [])
                    cloudflare/infer! (fn [& args] (swap! sends conj args) {:answers {}})]
        (let [r (run-script (command-plan command)
                           {:wire inference-wire :toolset t :options {:max-tool-calls limit}})]
          @sends => []
          (:used @(:budget (:request r))) => (if (= limit 1) 1 3)))))
  (let [sends (atom 0) wide (assoc inference-policies :name "wide" :native-tools ["command" "chart" "help"])]
    (with-redefs [ai/config (constantly {:providers [zen-provider]})
                    ai/prepare-provider (fn [p _] p)
                    context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                  ai/complete (fn [& _] (swap! sends inc) {:result/value "child"})]
      (run-script (command-plan "list A B | xargs zen --toolset wide --model paid")
                  {:wire inference-wire :toolsets [inference-policies wide]})
      @sends => 0)))

(fact "authorized xargs Zen and legacy OpenAI retain actor, exact physical admissions and generation ceilings"
  ;; Three admissions permit source + command + ONE send; four permit both
  ;; xargs sends. This is an explicit physical-source accounting regression.
  (doseq [command ["list A B | xargs zen --model paid --max-output-tokens 262144"
                   "list A B | xargs openai"]
          limit [3 4]]
    (let [children (atom [])]
      (with-redefs [ai/config (constantly {:providers [zen-provider]})
                    ai/prepare-provider (fn [p _] p)
                    openai/config {:key "synthetic-openai-key"}
                    context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                    http/post (fn [endpoint opts]
                                (swap! children conj [(or (get-in opts [:form-params :max_tokens])
                                                          (get-in opts [:form-params :max_completion_tokens]))
                                                      (policy/actor execution/*event*)
                                                      (:used @(:budget execution/*request*)) endpoint])
                                {:status 200 :body (answer "child")})]
        (let [r (run-script (command-plan command)
                           {:wire inference-wire :toolset inference-policies
                            :options {:max-output-tokens 1200 :max-tool-calls limit}})
              expected (- limit 2)]
          (mapv first @children) => (vec (repeat expected 1200))
          (mapv #(get-in % [1 :user]) @children) => (vec (repeat expected "U1"))
          (count @children) => expected
          (every? #(<= 3 (nth % 2) limit) @children) => true
          (:used @(:budget (:request r))) => limit)))))

(fact "failed weather labels come from bounded redacted source outcomes, not error prose or command text"
  (with-redefs [weather/current
                (fn [location]
                  (if (= location "A")
                    {:data [(assoc (first capture/rows) :city_name "A")]}
                    {:error "PRIVATE-ERROR-TREE-COMMAND"}))]
    (let [r (run-script
              (fn [n messages _]
                (if (= n 1)
                  (charts/call "collect" "command"
                    {"command" "list A SYNTHETIC-CREDENTIAL | xargs weather"})
                  (let [d (charts/descriptor messages)]
                    (get-in d ["coverage" "failed-inputs"]) => ["[redacted]"]
                    (charts/call "chart" "chart"
                      {"dataset_id" (get d "dataset-id") "kind" "bar" "x" "city_name" "y" "temp"}))))
              {:wire #(hooks/cmd-hook #"weather" #".+" weather/weather-cmd)
               :options {:additional-secrets ["SYNTHETIC-CREDENTIAL"]}})]
      (count (:renders r)) => 1
      (string/includes? (get-in r [:result :result/value]) "Missing inputs: [redacted]") => true
      (string/includes? (pr-str (:result r)) "SYNTHETIC-CREDENTIAL") => false
      (string/includes? (pr-str (:result r)) "PRIVATE-ERROR-TREE-COMMAND") => false)
    (run-script
      (fn [n messages _]
        (if (= n 1)
          (charts/call "collect" "command" {"command" "list A b c d e f g | xargs weather"})
          (let [labels (get-in (charts/descriptor messages) ["coverage" "failed-inputs"])]
            (count labels) => 5
            (every? #{"b" "c" "d" "e" "f" "g"} labels) => true
            (answer "Partial source coverage"))))
      {:wire #(hooks/cmd-hook #"weather" #".+" weather/weather-cmd)})))

(fact "structured classifiers remain automatic and each physical send shares the source allowance"
  (doseq [command ["list A B | xargs clef Is this A?" "list A B | xargs clairic demo"]]
    (let [sends (atom []) account (apply str (repeat 32 "a"))]
      (with-redefs [ai/config (constantly {:providers [] :intent-router {:account-id account}})
                    config/get-config (fn [spec path]
                                        (if (= path [:ai])
                                          {:value {:intent-router {:account-id account}}}
                                          (original-get-config spec path)))
                    clef/credential! (constantly "synthetic-structured-key")
                    images/images (constantly [])
                    http/post (fn [url opts]
                                (swap! sends conj [url (json/parse-string (:body opts) true)])
                                ;; Invalid response still records the real send;
                                ;; do not manufacture successful classifier data.
                                {:status 200 :body (java.io.ByteArrayInputStream.
                                                   (.getBytes "{\"success\":true,\"errors\":[],\"result\":{}}" "UTF-8"))})]
        (let [r (run-script (command-plan command)
                           {:wire inference-wire :toolset inference-policies
                            :options {:max-tool-calls 3}})]
          (count @sends) => 1
          (.startsWith (ffirst @sends) (str "https://api.cloudflare.com/client/v4/accounts/" account "/ai/run/")) => true
          (:used @(:budget (:request r))) => 3)))))

(def credential-collections (atom 0))
(defn credential-records [_]
  (swap! credential-collections inc)
  {:result/value "Collected one synthetic row"
   :result/data-collection [{:x "SYNTHETIC-CREDENTIAL" :y 1}]})

(fact "compiled source preflight preserves known nonexecution diagnostics without replaying collection"
  (reset! credential-collections 0)
  (let [r (run-script
            (fn [n messages _]
              (if (= n 1) (charts/call "collect" "command" {"command" "credential-records"})
                (charts/call "chart" "chart"
                  {"dataset_id" (get (charts/descriptor messages) "dataset-id")
                   "kind" "bar" "x" "x" "y" "y"})))
            {:wire #(hooks/cmd-hook #"credential-records" #".*" credential-records)
             :options {:additional-secrets ["SYNTHETIC-CREDENTIAL"]}})]
    @credential-collections => 1
    (count (:turns r)) => 2
    (:renders r) => []
    (get-in r [:result :result/data :execution :calls 1 :problem])
    => {:stage :arguments :field "*" :code :credential-input
        :remedy "Remove credentials from the request." :execution-began false}
    (string/includes? (get-in r [:result :result/value]) "before rendering") => true
    (string/includes? (get-in r [:result :result/value]) "execution did not begin") => true
    (string/includes? (pr-str (:result r)) "SYNTHETIC-CREDENTIAL") => false))
