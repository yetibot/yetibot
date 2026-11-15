(ns yetibot.test.util.ai-agent-execution
  (:require [midje.sweet :refer [fact facts => throws]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clj-http.client :as http]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.chat :as chat]
            [yetibot.core.adapters.slack :as slack]
            [yetibot.core.adapters.discord :as discord]
            [clj-slack.chat :as slack-chat]
            [yetibot.core.webapp.routes.images :as images]
            [yetibot.core.db.util :as db]
            [yetibot.core.db.agent-run :as ledger]
            [yetibot.core.db.cron :as cron]
            [yetibot.core.util.agent-execution :as origin]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-agent-runs :as runs]
            [yetibot.models.ai-datasets :as datasets]
            [yetibot.models.ai-kroki-tools :as kroki]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.util.ai-agent-execution :as agent]
            [yetibot.util.ai-execution :as execution]
            [yetibot.test.models.ai-agent-runs :as fixtures]
            [yetibot.test.support.ai-execution :as support]
            [yetibot.test.integration.ai-command-charts :as caller]))

(def provider {:name "local" :model "chat" :url "https://synthetic.invalid/v1"})
(def toolset {:name "bounded" :kind "search" :servers [{:name "source"}]
              :native-tools [] :users ["U1"] :adapters ["slack"]
              :providers [{:name "local" :models ["chat"]}]})
(def config {:servers [{:name "source" :url "https://source.invalid/mcp" :tools ["observe"]}]
             :toolsets [toolset]})
(def tool {:name "observe" :inputSchema {:type "object" :properties {}}
           :session {:server (first (:servers config))}})
(defn answer [text] {:choices [{:message {:role "assistant" :content text}}]})
(defn call [id name args]
  {:choices [{:message {:role "assistant" :content nil
                       :tool_calls [{:id id :type "function"
                                     :function {:name name :arguments (json/generate-string args)}}]}}]})
(defn row-for [url receipt]
  (let [id (:id (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run")
                                              " WHERE run_id=?") (:job-id receipt)])))]
    (ledger/read-row url "agent_run" id)))

(defn with-parent [f]
  (support/in-event support/request "ai synthetic"
    (fn [_]
      (let [request (merge (execution/family 20 91)
                          {:planner-provider provider :planner-model "chat"
                           :event execution/*event* :toolset-name "bounded"
                           :toolset-policy toolset
                           :tool-ceiling (atom #{["source" "observe"]})})]
        (binding [chat/*target* "C1"]
          (execution/with-request* request #(f request)))))))

(defn with-config [f]
  (with-redefs [mcp/config (constantly config)
                ai/config (constantly {:providers [provider]})
                mcp/with-tools (fn [_ f] (f [tool]))]
    (caller/with-current-caller f)))

(fact "loading the worker alone never installs a bridge or starts workers"
  (let [source (origin/host-callback :source)]
    (with-redefs [clojure.core/future-call (fn [_] (throw (AssertionError. "Namespace load started a worker")))]
      (require 'yetibot.util.ai-agent-execution :reload))
    (identical? source (origin/host-callback :source)) => true))

(fact "real worker uses selected provider, cap and loop with three distinct admissions"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (let [queued (atom []) sent (atom []) turns (atom []) effects (atom 0)
                backend (agent/callbacks {:database url :resolve-current identity
                                          :schedule #(swap! queued conj %)
                                          :deliver #(swap! sent conj [%1 %2])})]
            (with-redefs [http/post
                          (fn [endpoint opts]
                            (swap! turns conj [endpoint (:form-params opts)])
                            {:status 200 :body (if (= 1 (count @turns))
                                                (call "same-model-id" "mcp_0_source__observe" {})
                                                (answer "finished"))})
                          mcp/call-tool (fn [& _] (swap! effects inc)
                                         {:content [{:type "text" :text "synthetic observation"}]})]
              (with-parent
                (fn [request]
                  (dotimes [_ 3]
                    (execution/finish-operation!
                      (execution/admit-operation! :tool) {:state :definitive}))
                  (let [receipt ((:start backend) {:match ["agent" "do synthetic work"]})
                        row (row-for url receipt)]
                    (select-keys receipt [:status :provider :model :allocated :admitted :generation-limit])
                    => {:status :queued :provider "local" :model "chat" :allocated 17
                        :admitted 0 :generation-limit 91}
                    (:reserved @(:budget request)) => 17
                    (execution/close-family! request)
                    ((first @queued)) => {:status :unconfirmed :work-outcome :completed}
                    (mapv #(get-in % [1 :model]) @turns) => ["chat" "chat"]
                    (mapv #(get-in % [1 :max_completion_tokens]) @turns) => [91 91]
                    @effects => 1
                    (count @sent) => 1
                    (get-in @sent [0 0 :target]) => "C1"
                    (get-in @sent [0 0 :source :raw-event :user]) => "U1"
                    (get-in @sent [0 1 :result/value]) => "finished"
                    (let [final (ledger/read-row url "agent_run" (:id row))
                          receipts (json/parse-string (:operation-receipts final) true)]
                      (:execution-used final) => 3
                      (:execution-phase final) => "terminal"
                      (:execution-state final) => nil
                      (mapv :ordinal receipts) => [1 2 3]
                      (mapv :kind receipts) => ["inference" "tool" "inference"]
                      (mapv :parent-ordinal receipts) => [nil nil nil])))))))))))

(fact "safe model and per-tool checkpoints resume exact next operation and retained dataset IDs"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (doseq [boundary [:model :tool]]
          (let [turns (atom 0) effects (atom 0) queued (atom []) saved-id (atom nil)
                sent (atom []) checkpoint runs/checkpoint!
                backend (agent/callbacks {:database url :resolve-current identity
                                          :schedule #(swap! queued conj %)
                                          :deliver #(swap! sent conj [%1 %2])})]
            (with-redefs [http/post (fn [_ _]
                                      (swap! turns inc)
                                      (when (= 2 @turns)
                                        (let [request execution/*request*]
                                          (count (:rows (datasets/lookup! (:dataset-store request)
                                                         (:dataset-owner request) @saved-id))) => 25))
                                      {:status 200 :body (if (= 1 @turns)
                                                          (call "collect" "mcp_0_source__observe" {})
                                                          (answer "continued without collection"))})
                          mcp/call-tool
                          (fn [& _]
                            (swap! effects inc)
                            (let [request execution/*request*
                                  descriptor (datasets/retain! (:dataset-store request) (:dataset-owner request)
                                               {:rows (mapv #(hash-map "amount" (str % ".25")) (range 25))
                                                :fields [{:name "amount" :path ["amount"] :type :quantitative
                                                          :semantic :transaction-amount :unit "USD"}]
                                                :source "wise" :captured-at "2026-11-12T08:00:00Z"
                                                :coverage {:status :complete :requested 25 :succeeded 25 :failed 0}})]
                              (reset! saved-id (:dataset-id descriptor))
                              {:structuredContent {:dataset descriptor}}))]
              (with-parent
                (fn [_]
                  (let [receipt ((:start backend) "collect then answer")
                        initial (row-for url receipt)]
                    (with-redefs [runs/checkpoint! (fn [session state handle]
                                                    (checkpoint session state handle)
                                                    (when (if (= boundary :tool) (some? handle) (nil? handle))
                                                      (throw (ex-info "scripted crash after commit" {}))))]
                      ((first @queued)) => (throws Exception))
                    (:execution-phase (ledger/read-row url "agent_run" (:id initial))) => "ready"
                    ((:resume backend) (row-for url receipt))
                    @turns => 2
                    @effects => 1
                    (count @sent) => 1
                    (:execution-used (row-for url receipt)) => 3)))))))))))

(fact "model intent without durable response never replays on boot or late completion"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (let [wires (atom 0) queued (atom []) sent (atom 0)
                backend (agent/callbacks {:database url :resolve-current identity
                                          :schedule #(swap! queued conj %)
                                          :deliver (fn [& _] (swap! sent inc))})]
            (with-redefs [http/post (fn [& _] (swap! wires inc) (throw (Exception. "uncertain transport")))]
              (with-parent
                (fn [_]
                  (let [receipt ((:start backend) "synthetic")]
                    ((first @queued)) => (throws Exception)
                    @wires => 1
                    @sent => 0
                    (:execution-used (row-for url receipt)) => 1
                    (dotimes [_ 2]
                      ((:resume backend) (row-for url receipt)) => {:status :unknown})
                    @wires => 1
                    @sent => 0))))))))))

(fact "restart intersects original tools and rejects revoked authority or changed provider before IO"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (let [queued (atom []) wires (atom []) sent (atom 0)
                backend (agent/callbacks {:database url
                                          :resolve-current #(assoc % :tools #{["source" "observe"]
                                                                              ["yetibot" "command"]})
                                          :schedule #(swap! queued conj %) :deliver (fn [& _] (swap! sent inc))})
                denied (agent/callbacks {:database url :resolve-current (constantly nil)
                                         :schedule identity :deliver (fn [& _])})]
            (with-redefs [http/post (fn [_ opts]
                                      (swap! wires conj (:form-params opts))
                                      {:status 200 :body (answer "safe")})]
              (with-parent
                (fn [_]
                  (let [receipt ((:start backend) "synthetic")]
                    ((:resume denied) (row-for url receipt)) => (throws Exception)
                    @wires => []
                    (with-redefs [ai/config (constantly {:providers [(assoc provider :url "https://changed.invalid/v1")]})]
                      ((:resume backend) (row-for url receipt)) => (throws Exception))
                    @wires => []
                    ((:resume backend) (row-for url receipt))
                    (count @wires) => 1
                    (mapv #(get-in % [:function :name]) (:tools (first @wires)))
                    => ["mcp_0_source__observe"]
                    @sent => 1))))))))))

(fact "nested actual planner/tool/inference keeps distinct ancestor ownership"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (support/in-event support/request "ai nested"
            (fn [_]
          (let [row (fixtures/create! url "nested-loop" 8)
                session (fixtures/claim! url row)
                outer (runs/admit! session :command nil)
                requests (atom 0)
                request (merge (execution/family 8 32768)
                               {:budget (:budget session) :event execution/*event*
                                :loop-active? true
                                :resume-loop {:messages [{:role "user" :content "WRONG OUTER LOOP"}]
                                              :body (answer "must not reuse outer continuation")}
                                :stage-timings (atom {}) :planning-responses (atom 0)
                                :admit! #(runs/admit! session %1 %2)
                                :finish! #(runs/finish! session %1 %2)})]
            (with-redefs [http/post (fn [_ _]
                                      (swap! requests inc)
                                      {:status 200 :body (if (= 1 @requests)
                                                          (call "tool" "mcp_0_source__observe" {})
                                                          (answer "inner done"))})
                          mcp/call-tool (fn [& _]
                                          (execution/charge!)
                                          (ai/chat provider "chat" [{:role "user" :content "inner send"}]
                                                   {:max-output-tokens 100})
                                          {:content [{:type "text" :text "observed"}]})]
              (binding [origin/*operation* outer]
                (execution/with-request* request #(tools/complete provider "chat" [{:role "user" :content "nested"}]
                                                               {:actor (policy/actor execution/*event*)}))))
            (let [final (fixtures/read! url row)
                  receipts (json/parse-string (:operation-receipts final) true)]
              (:execution-phase final) => "operation-in-flight"
              (mapv :kind receipts) => ["command" "inference" "tool" "inference" "inference"]
              (mapv :parent-ordinal receipts) => [nil 1 1 3 1]
              (fixtures/claim! url final) => {:status :unknown}
              (:execution-used (fixtures/read! url row)) => 5)))))))))

(fact "loop dataset closes independently; expression closure seals family and late callbacks"
  (with-config
    (fn []
      (let [request (atom nil) store (atom nil)]
        (with-redefs [mcp/call-tool (fn [& _]
                                    (reset! request execution/*request*)
                                    (reset! store (:dataset-store execution/*request*))
                                    {:content [{:type "text" :text "observed"}]})
                      http/post (let [n (atom 0)]
                                  (fn [_ _] {:status 200 :body
                                             (if (= 1 (swap! n inc))
                                               (call "tool" "mcp_0_source__observe" {})
                                               (answer "done"))}))]
          (support/in-event support/request "ai synthetic"
            (fn [_]
              (origin/with-scope*
                (fn []
                  (tools/complete provider "chat" [{:role "user" :content "synthetic"}]
                                  {:toolset "bounded" :actor (policy/actor execution/*event*)})
                  (:closed? @(:budget @request)) => false
                  (:closed? @(:state @store)) => true))))
          (:closed? @(:budget @request)) => true
          (execution/with-request* @request #(execution/admit-operation! :inference)) => (throws Exception)
          (datasets/export-store! @store (:dataset-owner @request)) => (throws Exception))))))

(fact "exhausted queued jobs and exact last-unit jobs deliver without inventing a default"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (doseq [used [19 20]]
            (let [queued (atom []) wires (atom 0) sent (atom 0)
                  backend (agent/callbacks {:database url :resolve-current identity
                                            :schedule #(swap! queued conj %)
                                            :deliver (fn [& _] (swap! sent inc))})]
              (with-redefs [http/post (fn [& _] (swap! wires inc)
                                       {:status 200 :body (answer "last response")})]
                (with-parent
                  (fn [_]
                    (dotimes [_ used]
                      (execution/finish-operation!
                        (execution/admit-operation! :tool) {:state :definitive}))
                    (let [receipt ((:start backend) "finish")]
                      (:allocated receipt) => (- 20 used)
                      (:status receipt) => (if (= used 20) :exhausted :queued)
                      ((first @queued)) => {:status :unconfirmed :work-outcome :exhausted}
                      @wires => (- 20 used)
                      @sent => 1
                      (:execution-used (row-for url receipt)) => (- 20 used)
                      (:request (row-for url receipt))
                      => "Inherited allowance exhausted; delivery unconfirmed.")))))))))))

(fact "expired saved artifact is an honest limitation with no rendering, model or delivery replay"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (let [sent (atom 0) wires (atom 0)
                backend (agent/callbacks {:database url :resolve-current identity
                                          :schedule (fn [_]) :artifact-valid? (constantly false)
                                          :deliver (fn [& _] (swap! sent inc))})]
            (with-redefs [http/post (fn [& _] (swap! wires inc) (throw (Exception.)))
                          kroki/client-call (fn [& _] (throw (Exception. "must not render")))]
              (with-parent
                (fn [_]
                  (let [receipt ((:start backend) "synthetic")
                        session (fixtures/claim! url (row-for url receipt))]
                    (runs/save-result! session
                      {:result/value "saved chart"
                       :result/public-data {:artifacts [{:id "expired" :url "https://synthetic.invalid/expired.png"}]}}
                      :completed)
                    ((:resume backend) (row-for url receipt))
                    => (throws clojure.lang.ExceptionInfo
                               "Inherited artifact is expired or unavailable; no rendering was repeated.")
                    @wires => 0
                    @sent => 0))))))))))

(fact "parent dataset transfer preserves exact owner and handle after original store closes"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (let [queued (atom []) original-owner (atom nil) id (atom nil)
                backend (agent/callbacks {:database url :resolve-current identity
                                          :schedule #(swap! queued conj %) :deliver (fn [& _])})]
            (with-redefs [http/post (fn [& _]
                                      (:dataset-owner execution/*request*) => @original-owner
                                      (get-in (datasets/describe (:dataset-store execution/*request*)
                                                 @original-owner @id) [:coverage :status]) => :complete
                                      {:status 200 :body (answer "retained")})]
              (with-parent
                (fn [parent]
                  (let [owner {:actor (policy/actor execution/*event*)
                               :chat-source (:chat-source execution/*event*)}
                        store (datasets/create-store owner)
                        descriptor (datasets/retain! store owner
                                     {:rows [{:city "A" :temp 2}]
                                      :fields [{:name "city" :path ["city"] :type :nominal}
                                               {:name "temp" :path ["temp"] :type :quantitative}]
                                      :source "weather" :captured-at "2026-11-12T08:00:00Z"
                                      :coverage {:status :complete :requested 1 :succeeded 1 :failed 0}})]
                    (reset! id (:dataset-id descriptor)) (reset! original-owner owner)
                    (execution/with-request* (assoc parent :dataset-store store :dataset-owner owner)
                      #((:start backend) "use retained data"))
                    (datasets/close! store owner)
                    (execution/close-family! parent)
                    ((first @queued)) => {:status :unconfirmed :work-outcome :completed}))))))))))

(def chart-artifact {:type "vegalite" :format "png" :mime-type "image/png"
                     :bytes 64 :id "retained-chart" :ephemeral true
                     :url "https://bot.invalid/generated-images/retained-chart.png"})

(fact "restored complete finance dataset supports actual chart-only correction without collection replay"
  (fixtures/with-store
    (fn [url]
      (let [chartset (assoc toolset :native-tools ["chart"])
            chart-config (assoc config :toolsets [chartset])
            turns (atom 0) collections (atom 0) renders (atom []) queued (atom []) id (atom nil)
            checkpoint runs/checkpoint!
            backend (agent/callbacks {:database url :resolve-current identity
                                      :schedule #(swap! queued conj %) :artifact-valid? (constantly true)
                                      :deliver (fn [& _])})]
        (with-redefs [mcp/config (constantly chart-config)
                      ai/config (constantly {:providers [provider]})
                      mcp/with-tools (fn [_ f] (f [tool]))
                      http/post
                      (fn [_ _]
                        (swap! turns inc)
                        {:status 200 :body
                         (if (= 1 @turns) (call "collect" "mcp_0_source__observe" {})
                           (call (str "chart-" @turns) "yetibot_chart"
                                 {"dataset_id" @id "kind" "bar" "x" "date"
                                  "y" (if (= 2 @turns) "missing" "amount")}))})
                      mcp/call-tool
                      (fn [& _]
                        (swap! collections inc)
                        (let [request execution/*request*
                              descriptor
                              (datasets/retain! (:dataset-store request) (:dataset-owner request)
                                {:rows (mapv #(hash-map "date" (format "2026-11-%02d" (inc %))
                                                        "amount" (str % ".25")) (range 25))
                                 :fields [{:name "date" :path ["date"] :type :temporal}
                                          {:name "amount" :path ["amount"] :type :quantitative
                                           :semantic :transaction-amount :unit "USD"}]
                                 :source "wise" :captured-at "2026-11-12T08:00:00Z"
                                 :coverage {:status :complete :requested 25 :succeeded 25 :failed 0}})]
                          (reset! id (:dataset-id descriptor))
                          {:structuredContent {:dataset descriptor}}))
                      kroki/client-call
                      (fn [operation request]
                        (case operation
                          "validate-render!" request
                          "render!" (do (swap! renders conj request)
                                        (public/reviewed {:result/value (:url chart-artifact)
                                                          :result/data chart-artifact
                                                          :result/public-data chart-artifact}))))]
          (with-parent
            (fn [parent]
              (execution/with-request* (assoc parent :toolset-policy chartset
                                             :tool-ceiling (atom #{["source" "observe"] ["yetibot" "chart"]}))
                (fn []
                  (let [receipt ((:start backend) "chart complete financial data")]
                    (with-redefs [runs/checkpoint! (fn [session state handle]
                                                    (checkpoint session state handle)
                                                    (when handle (throw (Exception. "crash after collection checkpoint"))))]
                      ((first @queued)) => (throws Exception))
                    ((:resume backend) (row-for url receipt))
                    @collections => 1
                    @turns => 3
                    (count @renders) => 1
                    (count (get-in (json/parse-string (:source (first @renders)) true)
                                   [:data :values])) => 25
                    (:execution-used (row-for url receipt)) => 6
                    (:execution-state (row-for url receipt)) => nil))))))))))

(fact "actual Slack logging and Discord swallowed multipart failure never become confirmed delivery"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (doseq [mode [:slack :discord]]
            (let [parts (atom []) queued (atom [])
                  text (if (= mode :slack) "one saved answer"
                         (str "two artifacts https://bot.invalid/generated-images/one.png\n"
                              "https://bot.invalid/generated-images/two.png"))
                  backend (agent/callbacks
                            {:database url :resolve-current identity :schedule #(swap! queued conj %)
                             :deliver (fn [delivery result]
                                        (binding [chat/*target* (:target delivery)]
                                          (if (= mode :slack)
                                            (slack/send-msg {} (:result/value result))
                                            (#'discord/send-msg {:conn (atom {:rest :scripted})}
                                                               (:result/value result)))))})]
              (with-redefs [http/post (fn [& _] {:status 200 :body (answer text)})
                            slack-chat/post-message (fn [_ target body _]
                                                      (swap! parts conj [target body])
                                                      {:ok true :ts "message-id"})
                            images/image-store (atom {"one" {:data "AA==" :mime-type "image/png"}
                                                      "two" {:data "AA==" :mime-type "image/png"}})]
                (with-redefs-fn
                  {#'discord/create-message!
                   (fn [_ target part]
                     (swap! parts conj [target part])
                     (if (= 1 (count @parts)) {:id "first-part"}
                       (throw (Exception. "scripted second-part failure"))))}
                  (fn []
                    (with-parent
                      (fn [_]
                        (let [receipt ((:start backend) "deliver saved answer")]
                          ((first @queued)) => {:status :unconfirmed :work-outcome :completed}
                          (count @parts) => (if (= mode :slack) 1 2)
                          (set (map first @parts)) => #{"C1"}
                          (:execution-phase (row-for url receipt)) => "terminal"
                          ((:resume backend) (row-for url receipt)) => {:status :terminal}
                          (count @parts) => (if (= mode :slack) 1 2))))))))))))))

(fact "trusted cron restore has finite row counters and no fabricated new adapter event"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (let [resolved (atom [])
                backend (agent/callbacks {:database url :resolve-current #(do (swap! resolved conj %) %)
                                          :schedule (fn [_]) :deliver (fn [& _])})]
            (with-parent
              (fn [parent]
                (let [state (runs/cron-state
                              (assoc fixtures/state :authority (agent/authority parent)
                                     :generation-cap (:generation-limit parent)))
                      row (runs/transfer-live! url parent "cron"
                            {:schedule "* * * * *" :cmd "echo synthetic"
                             :execution-origin ledger/inherited-kind} state {})
                      claimed (jdbc/with-db-transaction [tx url] (cron/claim-tick! tx (:id row)))
                      restored ((:restore-cron backend) claimed)
                      session (:session restored) request (:request restored)
                      tick (:operation claimed)]
                  (:scheduled? request) => true
                  (some #(contains? (first @resolved) %) [:event :event-type :body :direct-input]) => nil
                  (some #(contains? request %) [:direct-input :direct-command]) => nil
                  (select-keys @(:budget session) [:limit :used :reserved])
                  => {:limit 20 :used 1 :reserved 0}
                  (runs/checkpoint! session @(:state session) tick)
                  (let [snapshot (runs/cron-snapshot (ledger/read-row url "cron" (:id row)) {})]
                    (get-in snapshot [:counters :execution-used]) => 1
                    (some #(contains? (:grant snapshot) %) [:loop :datasets :result :event]) => nil)
                  (execution/close-family! request)
                  (datasets/close! (:dataset-store request) (:dataset-owner request))
                  (let [fresh (ledger/read-row url "cron" (:id row))
                        denied (agent/callbacks {:database url :resolve-current
                                                 #(assoc-in % [:toolset-policy :users] ["REVOKED"])
                                                 :schedule (fn [_]) :deliver (fn [& _])})]
                    ((:restore-cron denied) fresh) => (throws Exception)))))))))))

(fact "delivery commit ambiguity and crash after send never repeat adapter submission"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (doseq [crash [:commit-ack :after-send]]
            (let [sent (atom 0)
                  backend (agent/callbacks {:database url :resolve-current identity
                                            :schedule (fn [_]) :deliver (fn [& _] (swap! sent inc))})]
              (with-parent
                (fn [_]
                  (let [receipt ((:start backend) "saved result")
                        session (fixtures/claim! url (row-for url receipt))]
                    (runs/save-result! session {:result/value "only saved data"} :completed)
                    (if (= crash :commit-ack)
                      (let [transaction jdbc/db-transaction* commits (atom 0)]
                        ;; First tx is claim; second is delivery-start CAS.
                        (with-redefs [jdbc/db-transaction*
                                      (fn [& args]
                                        (let [result (apply transaction args)]
                                          (if (and (= 2 (count args))
                                                   (not (:connection (first args)))
                                                   (= 2 (swap! commits inc)))
                                            (throw (Exception. "lost acknowledgement")) result)))]
                          ((:resume backend) (row-for url receipt)) => (throws Exception)))
                      (with-redefs [runs/finish-delivery! (fn [& _] (throw (Exception. "crash after send")))]
                        ((:resume backend) (row-for url receipt)) => (throws Exception)))
                    @sent => (if (= crash :commit-ack) 0 1)
                    (:phase (runs/decode (:execution-state (row-for url receipt)) {})) => :delivery-started
                    ((:resume backend) (row-for url receipt)) => {:status :unknown}
                    @sent => (if (= crash :commit-ack) 0 1)
                    (:execution-state (row-for url receipt)) => nil))))))))))

(fact "unsafe source data cannot become a durable job or disclose the rejected credential"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (let [scheduled (atom 0)
                backend (agent/callbacks {:database url :resolve-current identity
                                          :schedule (fn [_] (swap! scheduled inc)) :deliver (fn [& _])})]
            (with-redefs [ai/config (constantly {:providers [(assoc provider :key "PRIVATE_CHECKPOINT_CREDENTIAL")]})]
              (with-parent
                (fn [parent]
                  (let [error (try ((:start backend) "PRIVATE_CHECKPOINT_CREDENTIAL")
                                   (catch Exception e e))]
                    (instance? clojure.lang.ExceptionInfo error) => true
                    (boolean (re-find #"PRIVATE_CHECKPOINT" (.getMessage error))) => false
                    @scheduled => 0
                    (:reserved @(:budget parent)) => 0
                    (jdbc/query url [(str "SELECT count(*) n FROM " (db/qualified-table-name "agent_run"))])
                    => [{:n 0}])))))))))
)

(fact "nested durable delegation transfers before outer closure and reports queued, not completed"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (let [queued (atom []) turns (atom 0) sent (atom []) backend (atom nil)
                child (atom nil)]
            (reset! backend (agent/callbacks {:database url :resolve-current identity
                                             :schedule #(swap! queued conj %)
                                             :deliver #(swap! sent conj [%1 %2])}))
            (with-redefs [http/post (fn [& _]
                                      (swap! turns inc)
                                      {:status 200 :body (if (= 1 @turns)
                                                          (call "delegate" "mcp_0_source__observe" {})
                                                          (answer "child finished"))})
                          mcp/call-tool (fn [& _]
                                          (let [receipt ((:start @backend) "bounded child")]
                                            (reset! child receipt)
                                            {:structuredContent receipt}))]
              (with-parent
                (fn [parent]
                  (let [receipt ((:start @backend) "delegate once")]
                    (execution/close-family! parent)
                    ((first @queued)) => {:status :unconfirmed :work-outcome :queued}
                    @turns => 1
                    (get-in @sent [0 1 :result/public-data :agent-jobs 0 :job-id]) => (:job-id @child)
                    (get-in @sent [0 1 :result/value])
                    => (midje.sweet/contains "Accepted/queued inherited work (not completed)")
                    (select-keys (row-for url receipt) [:execution-used :execution-reserved])
                    => {:execution-used 2 :execution-reserved 18}
                    (:allocated @child) => 18
                    ((second @queued)) => {:status :unconfirmed :work-outcome :completed}
                    @turns => 2
                    (:execution-used (row-for url @child)) => 1
                    (:execution-reserved (row-for url receipt)) => 18))))))))))

(fact "a prepared key-file credential is rejected before durable reservation"
  (fixtures/with-store
    (fn [url]
      (with-config
        (fn []
          (with-redefs [ai/config (constantly {:providers [(assoc provider :key-file "/synthetic/provider-key")]})]
            (let [queued (atom []) backend (agent/callbacks {:database url :resolve-current identity
                                                            :schedule #(swap! queued conj %)
                                                            :deliver (fn [& _])})]
              (with-parent
                (fn [parent]
                  (execution/with-request* (assoc parent :planner-provider
                                                 (assoc provider :key "SYNTHETIC-LOADED-KEY"))
                    #((:start backend) "do not persist SYNTHETIC-LOADED-KEY")) => (throws Exception)
                  (:reserved @(:budget parent)) => 0
                  (:used @(:budget parent)) => 0
                  @queued => [])))))))))

(fact "scheduler throw after a worker starts cannot release that running process-local owner"
  (let [entered (promise) release (promise) worker (atom nil) exits (atom 0)
        scheduled (atom 0) row {:id (System/nanoTime)}
        resume (fn [_] (deliver entered true) @release (swap! exits inc))
        schedule (fn [f]
                   (reset! worker (future (f)))
                   (deref entered 5000 false)
                   (throw (Exception. "scripted scheduler acknowledgement failure")))]
    (try
      (#'agent/schedule-worker! "local-owner-fixture" row schedule resume)
      (deref entered 5000 false) => true
      (#'agent/schedule-worker! "local-owner-fixture" row (fn [_] (swap! scheduled inc)) resume)
      @scheduled => 0
      (finally (deliver release true)))
    (deref @worker 5000 :timeout) => 1
    @exits => 1
    ;; Only local liveness is tested here; real worker/SQL completion is covered
    ;; by integration.ai-agent-execution's blocked physical-send regression.
    (#'agent/schedule-worker! "local-owner-fixture" row (fn [f] (f)) (fn [_] :exited)) => :exited))


(def capture-attrs {:provider "local" :model "chat" :operation "text"
                    :endpoint "https://synthetic.invalid/v1/chat/completions"})

(defn completion-case [diagnostic nested?]
  (with-parent
    (fn [request]
      (let [finishes (atom []) finish usage/*finish-inference*
            error (ex-info "Synthetic host rejection" {:ai/diagnostic diagnostic})]
        (binding [usage/*finish-inference*
                  (fn [handle outcome]
                    (swap! finishes conj [handle outcome])
                    (finish handle outcome))]
          (try
            (#'agent/with-chat-completion*
              (fn []
                (let [send #(usage/capture! capture-attrs (fn [] (throw error)) identity)]
                  (if nested? (#'agent/with-chat-completion* send) (send)))))
            (catch Exception _ nil)))
        {:budget @(:budget request) :finishes @finishes
         :next (try (execution/admit-operation! :tool) :allowed
                    (catch Exception _ :denied))}))))

(facts "source-chat classifies only host definite HTTP rejections before the one physical finish"
  (doseq [status [400 401 403 404 405 413 415 422 429]
          nested? [false true]]
    (let [{:keys [budget finishes next]} (completion-case {:failure :http :status status} nested?)]
      (:used budget) => 1
      (:unknown? budget) => nil
      (mapv #(select-keys (second %) [:state :sent? :failed?]) finishes)
      => [{:state :definitive :sent? true :failed? true}]
      next => :allowed))
  (doseq [diagnostic [{:failure :transport :status 422}
                      {:failure :http :status 500}
                      {:failure :http :status :unrecognized}
                      {:body {:failure :http :status 422}}]
          nested? [false true]]
    (let [{:keys [budget finishes next]} (completion-case diagnostic nested?)]
      (:used budget) => 1
      (:unknown? budget) => true
      (mapv #(select-keys (second %) [:state :sent? :failed?]) finishes)
      => [{:state :unknown :sent? true :failed? true}]
      next => :denied)))

(fact "swallowing an unfinished failure cannot admit a host effect or another inference"
  (with-parent
    (fn [request]
      (let [sends (atom 0) denied (atom [])]
        (#'agent/with-chat-completion*
          (fn []
            (try (usage/capture! capture-attrs
                   #(do (swap! sends inc) (throw (Exception. "Synthetic transport failure"))) identity)
                 (catch Exception _ nil))
            (doseq [attempt [#(execution/admit-operation! :tool)
                             #(usage/capture! capture-attrs (fn [] (swap! sends inc)) identity)]]
              (swap! denied conj (try (attempt) false (catch Exception _ true))))))
        @denied => [true true]
        @sends => 1
        (:used @(:budget request)) => 1
        (:unknown? @(:budget request)) => true))))

(fact "successful output and later semantic failure retain one definitive charge"
  (with-parent
    (fn [request]
      (let [finishes (atom 0) finish usage/*finish-inference*]
        (binding [usage/*finish-inference* (fn [handle outcome] (swap! finishes inc) (finish handle outcome))]
          (try (#'agent/with-chat-completion*
                 #(do (usage/capture! capture-attrs (constantly :response) identity)
                      (throw (ex-info "Synthetic output limit" {:ai/output-error :output-limit}))))
               (catch Exception _ nil)))
        @finishes => 1
        (:used @(:budget request)) => 1
        (mapv :state (:receipts @(:budget request))) => [:definitive]
        (:unknown? @(:budget request)) => nil))))

(fact "not-sent admission denial never creates a completion or charge"
  (with-parent
    (fn [request]
      (let [finishes (atom 0) sends (atom 0)]
        (binding [usage/*admit-inference* (fn [_] (throw (ex-info "Not sent" {:ai/request-not-sent true})))
                  usage/*finish-inference* (fn [& _] (swap! finishes inc))]
          (try (#'agent/with-chat-completion*
                 #(usage/capture! capture-attrs (fn [] (swap! sends inc)) identity))
               (catch Exception _ nil)))
        @finishes => 0
        @sends => 0
        (:used @(:budget request)) => 0))))

(fact "upstream completion failure seals the same family without replaying the callback"
  (with-parent
    (fn [request]
      (let [finishes (atom 0)]
        (binding [usage/*finish-inference* (fn [& _] (swap! finishes inc) (throw (Exception. "Upstream finish failed")))]
          (try (#'agent/with-chat-completion*
                 #(usage/capture! capture-attrs
                    (fn [] (throw (ex-info "Synthetic rejection" {:ai/diagnostic {:failure :http :status 422}}))) identity))
               (catch Exception _ nil)))
        @finishes => 1
        (:used @(:budget request)) => 1
        (:closed? @(:budget request)) => true
        (execution/admit-operation! :tool) => (throws Exception)))))


(fact "a forged failed completion has no admitted identity and never reaches the original finish"
  (with-parent
    (fn [request]
      (let [finishes (atom 0)]
        (binding [usage/*finish-inference* (fn [& _] (swap! finishes inc))]
          (try (#'agent/with-chat-completion*
                 #(usage/*finish-inference* {:family-id (:family-id request) :ordinal 1}
                                            {:state :unknown :sent? true :failed? true}))
               (catch Exception _ nil)))
        @finishes => 0
        (:used @(:budget request)) => 0
        (:closed? @(:budget request)) => true))))

(fact "duplicate pending completion flushes the real unknown once before sealing"
  (with-parent
    (fn [request]
      (let [finishes (atom 0) finish usage/*finish-inference*]
        (binding [usage/*finish-inference* (fn [handle outcome] (swap! finishes inc) (finish handle outcome))]
          (try (#'agent/with-chat-completion*
                 #(let [handle (usage/*admit-inference* capture-attrs)
                        outcome {:state :unknown :sent? true :failed? true}]
                    (usage/*finish-inference* handle outcome)
                    (usage/*finish-inference* handle outcome)))
               (catch Exception _ nil)))
        @finishes => 1
        (:used @(:budget request)) => 1
        (:unknown? @(:budget request)) => true
        (:closed? @(:budget request)) => true))))

(fact "even an upstream finish error after a successful response cannot reopen that allocation"
  (with-parent
    (fn [request]
      (let [finishes (atom 0)]
        (binding [usage/*finish-inference* (fn [& _] (swap! finishes inc) (throw (Exception. "Upstream finish failed")))]
          (try (#'agent/with-chat-completion*
                 #(usage/capture! capture-attrs (constantly :response) identity))
               (catch Exception _ nil)))
        @finishes => 1
        (:used @(:budget request)) => 1
        (:closed? @(:budget request)) => true))))
