(ns yetibot.util.ai-agent-execution
  "Host-owned worker callbacks for the existing planner loop. Loading this
   namespace installs nothing. Public command/origin/scheduler wiring is owned
   by the command bridge, not by the checkpoint codec."
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.db.agent-run :as ledger]
            [yetibot.core.interpreter :as interpreter]
            [yetibot.core.chat :as chat]
            [yetibot.core.util.public-result :as public-result]
            [yetibot.models.ai-agent-runs :as runs]
            [yetibot.models.ai-datasets :as datasets]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.util.ai-execution :as execution]))

(defn- denied! []
  (throw (ex-info "Inherited execution authority is unavailable or changed."
                  {:ai/error true :ai/request-not-sent true})))

(defn- saved-source [source]
  (let [raw (:raw-event source)]
    (-> (select-keys source [:adapter :uuid :room])
        (assoc :raw-event
               (cond-> (select-keys raw [:user :channel :ts :thread_ts :id :channel-id])
                 (:author raw) (assoc :author (select-keys (:author raw) [:id :bot])))))))

(defn- identity-fields [user]
  (select-keys user [:id :username :name :yetibot?]))

(defn authority
  "Issue only from live host bindings; model/job JSON is not an input."
  [request]
  (let [event execution/*event* actor (policy/actor event)
        provider (:planner-provider request) model (:planner-model request)]
    (when-not (and actor (:id (:yetibot-user event)) provider (ai/nonblank-string? model)
                   (:metered? request) (not (:closed? @(:budget request)))
                   (ai/nonblank-string? (chat/command-target))
                   (= event (:event request))) (denied!))
    {:source (saved-source (:chat-source event))
     :delivery {:source (saved-source (:chat-source event))
                :target (chat/command-target) :thread-ts chat/*thread-ts*}
     :user (identity-fields (:user event))
     :yetibot-user (identity-fields (:yetibot-user event))
     :actor actor :toolset-name (:toolset-name request)
     :toolset-policy (:toolset-policy request)
     :tools (or (some-> (:tool-ceiling request) deref) #{})
     :planner {:name (:name provider) :model model
               :signature (execution/provider-signature provider)}}))

(defn- event-for [grant]
  {:chat-source (:source grant) :user (:user grant)
   :yetibot-user (:yetibot-user grant) :event-type :message})

(defn- authorize-inference! [original current attrs]
  (when (= "agent" (:operation attrs)) (denied!))
  (let [configured (ai/provider (ai/config) (:provider attrs))
        prepared (ai/prepare-provider configured (:model attrs))
        cap (:output-cap attrs)]
    (when-not (and (= (:provider-signature attrs)
                      (execution/provider-signature prepared))
                   (string? (:endpoint attrs))
                   (or (nil? cap)
                       (and (integer? cap) (<= 1 cap (:generation-cap current)))))
      (denied!))
    (when (and (= "text" (:operation attrs)) (nil? cap)) (denied!))
    (doseq [grant [original current]]
      (policy/authorize! (:toolset-policy grant) (:actor grant)
                         prepared (:model attrs)))))

(defn- resolve-authority! [resolve-current state]
  ;; This callback is captured by trusted initialization, never read from JSON.
  ;; It must find the real configured adapter/user and current policy; no fake
  ;; adapter event is presented to the resolver as evidence of a new message.
  (let [original (:authority state)
        current (resolve-current original)
        planner (:planner original)
        provider (ai/prepare-provider
                   (ai/provider (ai/config) (:name planner)) (:model planner))]
    (when-not (and current
                   (= (select-keys original [:source :user :yetibot-user :actor])
                      (select-keys current [:source :user :yetibot-user :actor]))
                   (= (:signature planner) (execution/provider-signature provider)))
      (denied!))
    (doseq [grant [original current]]
      (policy/authorize! (:toolset-policy grant) (:actor original)
                         provider (:model planner)))
    {:original original :current (assoc current :generation-cap (:generation-cap state))
     :provider provider :model (:model planner)
     :tools (set/intersection (:tools original) (:tools current))}))

(defn- tool-key [tool]
  [(if (:native? tool) "yetibot" (get-in tool [:session :server :name])) (:name tool)])

(defn- request-for [session resolved]
  (let [state @(:state session) grant (:original resolved)
        event (event-for grant)
        owner (or (get-in state [:datasets :owner])
                  {:actor (:actor grant) :chat-source (:source grant)})
        _ (when-not (and (= (:actor grant) (:actor owner))
                         (= (:source grant) (saved-source (:chat-source owner)))) (denied!))
        config (:codec-config session)
        store (if (:datasets state)
                (datasets/import-store! owner (:datasets state) config)
                (datasets/create-store owner config))
        request {:family-id (:family-id state) :metered? true
                 :usage-context-id (:usage-context-id state)
                 :budget (:budget session) :event event
                 :durable-session session :scheduled? (:scheduled? state)
                 :generation-limit (:generation-cap state)
                 :planner-provider (:provider resolved) :planner-model (:model resolved)
                 :toolset-name (:toolset-name grant)
                 :toolset-policy (execution/intersect-policy (:toolset-policy grant)
                                                              (get-in resolved [:current :toolset-policy]))
                 :tool-ceiling (atom (:tools resolved))
                 :accepted-jobs (atom (get-in state [:loop :accepted-jobs] []))
                 :allowed-tool? #(contains? (:tools resolved) (tool-key %))
                 :stage-timings (atom (get-in state [:loop :stage-timings] {}))
                 :planning-responses (atom (get-in state [:loop :planning-responses] 0))
                 :dataset-store store :dataset-owner owner :resume-loop (when (= :loop (:phase state)) (:loop state))
                 :authorize-inference! #(authorize-inference! grant (:current resolved) %)
                 :admit! (fn [kind parent] (runs/admit! session kind parent))
                 :finish! #(runs/finish! session %1 %2)}]
    (assoc request :checkpoint-loop!
           (fn [loop-state handle]
             (runs/checkpoint! session
               (assoc @(:state session) :phase :loop :loop loop-state
                      :datasets (datasets/export-store! store owner)) handle)))))

(defn callbacks
  "Construct trusted start/resume callbacks; install them only from the bridge.
   resolve-current reauthorizes real saved identity using current configuration;
   deliver sends ONLY to the saved source/target, and returns no invented receipt.
   schedule is invoked only after the allocation transaction definitely commits."
  [{:keys [database resolve-current deliver schedule codec-config artifact-valid?]
    :or {schedule #(future (%)) codec-config {} artifact-valid? (constantly false)}}]
  (when-not (and database (fn? resolve-current) (fn? deliver) (fn? schedule))
    (denied!))
  (letfn [(codec-config! []
            (let [providers (:providers (ai/config))
                  config (merge (mcp/config) codec-config)]
              (update config :secrets concat
                      (mapcat #(keep identity [(:key %) (:key-file %)]) providers))))
          (resume! [row]
            (when-not (= ledger/inherited-kind (:execution-kind row)) (denied!))
            (let [session (runs/claim! database "agent_run" (:id row)
                                      (:state-revision row) (codec-config!))]
              (if (not= :claimed (:status session)) (select-keys session [:status])
                (let [state @(:state session)
                      resolved (resolve-authority! resolve-current state)
                      config (update (:codec-config session) :secrets conj (:key (:provider resolved)))
                      _ (runs/decode (:execution-state @(:row session)) config)
                      session (assoc session :codec-config config)
                      artifacts (or (get-in state [:loop :artifacts])
                                    (get-in state [:result :result/public-data :artifacts]))]
                  (when-not (every? artifact-valid? artifacts)
                    (throw (ex-info "Inherited artifact is expired or unavailable; no rendering was repeated."
                                    {:ai/error true :ai/request-not-sent true})))
                  (cond
                    (= :result-saved (:phase state)) (publish! session)
                    (zero? (- (:limit @(:budget session)) (:used @(:budget session))
                              (:reserved @(:budget session))))
                    (let [jobs (get-in state [:loop :accepted-jobs])]
                      (runs/save-result! session
                        {:result/value (if (seq jobs)
                                         "Inherited child work is queued, not completed; no unreserved allowance remains."
                                         "Inherited allowance exhausted; no further inference or tool was dispatched.")
                         :result/public-data {:agent-jobs jobs}}
                        (if (seq jobs) :queued :exhausted))
                      (publish! session))
                    :else
                    (let [request (request-for session resolved)
                          event (:event request)]
                      (try
                        (binding [execution/*event* event
                                  execution/*direct-input* nil execution/*direct-command* nil
                                  execution/*command-capture* true execution/*sensitive* (atom true)
                                  interpreter/*chat-source* (:chat-source event)
                                  interpreter/*current-user* (:user event)
                                  interpreter/*yetibot-user* (:yetibot-user event)]
                          (execution/with-request* request
                            (fn []
                              (let [result
                                    (try
                                      (tools/complete (:provider resolved) (:model resolved)
                                        (get-in state [:loop :messages])
                                        {:actor (:actor (:original resolved))
                                         :toolset (:toolset-name request)
                                         :max-output-tokens (:generation-limit request)})
                                      (catch Exception e
                                        (if (and (zero? (execution/remaining))
                                                 (= :budget-exceeded
                                                    (get-in (ex-data e) [:ai/tool-failure :reason])))
                                          {:result/value "Inherited allowance exhausted; no further inference or tool was dispatched."
                                           :result/data {:isError true}}
                                          (throw e))))
                                    projection (public-result/projection result)
                                    saved {:result/value (:result/value result)
                                           :result/public-data (:result/public-data projection)}]
                                (runs/save-result! session saved
                                  (cond (seq @(:accepted-jobs request)) :queued
                                        (zero? (execution/remaining)) :exhausted
                                        (get-in result [:result/data :isError]) :partial
                                        :else :completed))
                                (publish! session)))))
                        (finally
                          (execution/close-family! request)
                          (datasets/close! (:dataset-store request) (:dataset-owner request))))))))))
          (publish! [session]
            (let [result (runs/begin-delivery! session)
                  grant (:authority @(:state session))
                  _ (try
                      ;; Slack logging/Discord multipart nonthrowing returns
                      ;; are NOT confirmed complete delivery receipts.
                      (deliver (:delivery grant) result)
                      (catch Throwable _ nil))]
              (runs/finish-delivery! session)
              {:status :unconfirmed :work-outcome (:work-outcome @(:state session))}))
          (start! [input]
            (let [prompt (if (string? input) input (second (:match input)))
                  _ (when-not (ai/nonblank-string? prompt) (denied!))
                  request execution/*request* grant (authority request)
                  codec-config (update (codec-config!) :secrets conj
                                       (:key (:planner-provider request)))
                  job-id (str (java.util.UUID/randomUUID))
                  retained (when-let [store (:dataset-store request)]
                             (datasets/export-store! store (:dataset-owner request)))
                  state (cond-> {:job-id job-id :family-id (:family-id request)
                         :parent-id (some-> (:durable-session request) :state deref :job-id)
                         :authority grant :generation-cap (:generation-limit request)
                         :usage-context-id (:usage-context-id request)
                         :scheduled? (boolean (:scheduled? request))
                         :phase :queued
                         :loop {:messages
                                (cond-> [{:role "user" :content prompt}]
                                  (seq (:datasets retained))
                                  (conj {:role "user"
                                         :content (json/generate-string
                                                    {:source "yetibot-retained-datasets" :untrusted true
                                                     :datasets
                                                     (mapv #(datasets/describe (:dataset-store request)
                                                                               (:dataset-owner request) %)
                                                           (keys (:datasets retained)))})}))}}
                          retained (assoc :datasets retained))
                  entity {:run-id job-id :request "Inherited work queued."
                          :adapter-uuid (str (get-in grant [:source :uuid]))}
                  row (if-let [session (:durable-session request)]
                        (locking (:budget session)
                          (let [child (jdbc/with-db-transaction [tx database]
                                        (runs/transfer! tx (:owner session) "agent_run"
                                                        entity state codec-config))
                                parent (ledger/read-row database (get-in session [:owner :table])
                                                        (get-in session [:owner :id]))]
                            (reset! (:row session) parent)
                            (swap! (:budget session) merge (runs/counters parent))
                            child))
                        (runs/transfer-live! database request "agent_run" entity state codec-config))
                  receipt {:status (if (zero? (:execution-limit row)) :exhausted :queued)
                           :job-id job-id :provider (get-in grant [:planner :name])
                           :model (get-in grant [:planner :model]) :allocated (:execution-limit row)
                           :admitted 0 :generation-limit (:generation-limit request)}]
              (when-let [accepted (:accepted-jobs request)] (swap! accepted conj receipt))
              ;; A scheduling failure does not undo a committed allocation or
              ;; justify a replacement job. The same queued ID is boot-resumable.
              (try (schedule #(resume! row)) (catch Throwable _ nil))
              receipt))]
    {:start start! :resume resume!
     :restore-cron
     (fn [row]
       (when-not (= ledger/inherited-kind (:execution-origin row)) (denied!))
       (let [session (runs/claim! database "cron" (:id row) (:state-revision row)
                                  (codec-config!))]
         (when-not (= :claimed (:status session)) (denied!))
         (runs/cron-snapshot @(:row session) (:codec-config session))
         ;; The actual scheduler owns tick admission/binding/outer completion.
         ;; Restoring this private grant is not evidence of a new adapter event.
         {:session session
          :request (request-for session
                     (resolve-authority! resolve-current @(:state session)))}))}))
