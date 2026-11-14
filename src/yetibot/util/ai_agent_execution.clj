(ns yetibot.util.ai-agent-execution
  "Host-owned worker callbacks for the existing planner loop. Loading this
   namespace installs nothing. Public command/origin/scheduler wiring is owned
   by the command bridge, not by the checkpoint codec."
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as string]
            [robert.hooke :as hooke]
            [yetibot.core.adapters.adapter :as adapters]
            [yetibot.core.models.users :as users]
            [yetibot.core.commands.alias :as aliases]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.db.util :as db]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.core.util.xai :as xai]
            [yetibot.api.cloudflare :as cloudflare]
            [yetibot.api.clef :as clef]
            [yetibot.core.util.agent-execution :as origin]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.core.util.command :as command]
            [yetibot.core.parser :as parser]
            [yetibot.core.webapp.routes.images :as images]
            [yetibot.models.ai-command-tools :as commands]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.util.ai-limits :as limits]
            [yetibot.api.ai :as ai]
            [yetibot.api.opencode :as opencode]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.db.agent-run :as ledger]
            [yetibot.core.db.cron :as cron]
            [yetibot.core.handler :as handler]
            [yetibot.models.ai-cron-tools :as cron-tools]
            [yetibot.core.interpreter :as interpreter]
            [yetibot.core.chat :as chat]
            [yetibot.core.util.public-result :as public-result]
            [yetibot.models.ai-agent-runs :as runs]
            [yetibot.models.ai-datasets :as datasets]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-native-tools :as native]
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

(defn- native-text-provider [transport]
  (case transport
    "xai-text" (when (xai/configured?)
                 {:name "xai" :url "https://api.x.ai/v1" :api "responses"
                  :key (:key xai/config) :continuation-transport "xai-text"})
    "openai-text" (when-let [key (some-> (find-ns 'yetibot.commands.openai)
                                        (ns-resolve 'config) deref :key)]
                    {:name "openai" :url "https://api.openai.com/v1" :key key
                     :continuation-transport "openai-text"})
    nil))

(defn- planner-identity [provider model]
  (cond-> {:name (:name provider) :model model
           :signature (execution/provider-signature provider)}
    (:continuation-transport provider) (assoc :transport (:continuation-transport provider))))

(defn- prepare-source-provider [provider model]
  ;; Identity checks must not resolve an ordinary provider's key-file. Zen
  ;; needs protocol metadata, but only after the caller's policy is checked.
  (if (opencode/provider? provider)
    (ai/prepare-provider provider model)
    provider))

(defn- resolve-planner-provider [planner]
  (let [provider (if-let [transport (:transport planner)]
                   (native-text-provider transport)
                   (ai/provider (if (= "grok" (:name planner))
                                  (ai/grok-config) (ai/config))
                                (:name planner)))]
    (when-not (and provider
                   (or (nil? (:transport planner))
                       (= (:model planner) (case (:transport planner)
                                              "xai-text" "grok-4.7" "openai-text" "gpt-4" nil))))
      (denied!))
    provider))

(defn- resolve-planner [planner]
  (prepare-source-provider (resolve-planner-provider planner) (:model planner)))

(defn- resolve-producer [producer]
  (case (:transport producer)
    ("xai-text" "openai-text" nil) (resolve-planner producer)
    "xai-image" (when (and (xai/configured?) (= "grok-imagine-image-2.0" (:model producer)))
                  {:name "xai" :url "https://api.x.ai/v1"})
    "gemini-image" (when (and (gemini/configured?) (= (gemini/gemini-model) (:model producer)))
                     {:name "gemini" :url "https://generativelanguage.googleapis.com/v1beta"})
    "gemini-video" (when (gemini/configured?)
                     {:name "gemini" :url "https://generativelanguage.googleapis.com/v1beta"})
    "cloudflare-classifier"
    (when (#{"@cf/cloudflare/clef" "@cf/cloudflare/clairic"} (:model producer))
      {:name "cloudflare" :url (cloudflare/account-url (:account-id (:intent-router (ai/config))))})
    nil))

(defn- provider-identities []
  (let [config (ai/grok-config)
        native (keep identity
                 [(when (xai/configured?) {:name "xai" :url "https://api.x.ai/v1"})
                  (when (gemini/configured?) {:name "gemini" :url "https://generativelanguage.googleapis.com/v1beta"})
                  (when (native-text-provider "openai-text") {:name "openai" :url "https://api.openai.com/v1"})
                  (when-let [account (get-in config [:intent-router :account-id])]
                    {:name "cloudflare" :url (cloudflare/account-url account)})])]
    (into {} (concat
               (map (fn [p] [(str "app:" (:name p)) (execution/provider-signature p)]) (:providers config))
               (map (fn [p] [(str "native:" (:name p)) (execution/provider-signature p)]) native)))))

(defn authority
  "Issue only from live host bindings; model/job JSON is not an input."
  [request]
  (when (or (:routing-pending? request) (:no-host-execution? request)) (denied!))
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
     :actor actor :no-tools? (execution/text-only?) :toolset-name (:toolset-name request)
     :toolset-policy (:toolset-policy request)
     :tools (or (some-> (:tool-ceiling request) deref) #{})
     :server-identities
     (into {} (map (fn [server]
                     [(:name server) (select-keys server [:name :url :command :args])])
                   (:servers (toolsets/select (mcp/config) (:toolset-name request)))))
     :provider-identities (or (:provider-identities request) (provider-identities))
     :producer (or (:producer request) (planner-identity provider model))
     :planner (planner-identity provider model)}))

(defn- event-for [grant]
  {:chat-source (:source grant) :user (:user grant)
   :yetibot-user (:yetibot-user grant) :event-type :message})

(defn- native-wire-provider [attrs]
  (let [{:keys [provider model operation endpoint output-cap resource-limits]} attrs
        cap-field (when (map? output-cap) (:field output-cap))
        configured (case provider
                     "xai" (when (xai/configured?)
                             (and (case operation
                                    "text" (and (= model "grok-4.7")
                                                (= endpoint "https://api.x.ai/v1/responses")
                                                (= cap-field "max_output_tokens"))
                                    "image" (and (= model "grok-imagine-image-2.0")
                                                 (#{"https://api.x.ai/v1/images/edits"
                                                    "https://api.x.ai/v1/images/generations"} endpoint)
                                                 (= {:images 1} resource-limits))
                                    false)
                                  {:name "xai" :url "https://api.x.ai/v1" :api :responses}))
                     "gemini" (when (gemini/configured?)
                                (and (case operation
                                       "image" (and (= model (gemini/gemini-model))
                                                    (= cap-field "generationConfig.maxOutputTokens")
                                                    (= 1 (:candidate-count output-cap))
                                                    (= endpoint (str "https://generativelanguage.googleapis.com/v1beta/models/"
                                                                     model ":generateContent")))
                                       "video" (and (= endpoint (str "https://generativelanguage.googleapis.com/v1beta/models/"
                                                                 model ":predictLongRunning"))
                                                    (integer? (:duration-seconds resource-limits))
                                                    (pos? (:duration-seconds resource-limits)))
                                       false)
                                     {:name "gemini" :url "https://generativelanguage.googleapis.com/v1beta"}))
                     "openai" (when (some-> (find-ns 'yetibot.commands.openai) (ns-resolve 'config) deref :key)
                                (and (= model "gpt-4") (= operation "text")
                                     (= endpoint "https://api.openai.com/v1/chat/completions")
                                     {:name "openai" :url "https://api.openai.com/v1"}))
                     "cloudflare"
                     (let [config (:intent-router (ai/config))]
                       (when (and (= operation "classification")
                                  (#{"@cf/cloudflare/clef" "@cf/cloudflare/clairic"} model)
                                  (= endpoint (str (cloudflare/account-url (:account-id config))
                                                   "/ai/run/" model))
                                  (integer? (:schema-fields resource-limits))
                                  (<= 1 (:schema-fields resource-limits) 64))
                         {:name "cloudflare" :url (cloudflare/account-url (:account-id config))}))
                     nil)]
    configured))

(defn- authorize-inference! [original current attrs]
  (when (= "agent" (:operation attrs)) (denied!))
  (let [configured (some #(when (= (:provider attrs) (:name %)) %) (:providers (ai/grok-config)))
        prepared (when configured
                   (doseq [grant [original current]]
                     (policy/authorize! (:toolset-policy grant) (:actor grant)
                                        configured (:model attrs)))
                   (prepare-source-provider configured (:model attrs)))
        app-wire? (and prepared
                       (some #{(:provider-signature attrs)}
                             (map execution/provider-signature
                                  [prepared (assoc prepared :native-tools []) (dissoc prepared :native-tools)]))
                       (= (str (string/replace (:url prepared) #"/+$" "")
                               (if (ai/responses? prepared) "/responses" "/chat/completions"))
                          (:endpoint attrs)))
        actual (if app-wire? prepared (native-wire-provider attrs))
        cap (if (map? (:output-cap attrs)) (get-in attrs [:output-cap :value]) (:output-cap attrs))
        key (str (if app-wire? "app:" "native:") (:provider attrs))
        before (get (:provider-identities original) key)
        now (get (provider-identities) key)]
    (when (and (:provider-identities original) (not (and before (= before now)))) (denied!))
    (when-not (and actual
                   (if (or (= "text" (:operation attrs))
                           (and (= "gemini" (:provider attrs)) (= "image" (:operation attrs))))
                     (and (integer? cap) (<= 1 cap (:generation-cap current)))
                     (or (nil? cap) (and (integer? cap) (<= 1 cap (:generation-cap current))))))
      (denied!))
    (doseq [grant [original current]]
      (policy/authorize! (:toolset-policy grant) (:actor grant) actual (:model attrs)))))

(defn- resolve-authority! [resolve-current state]
  ;; This callback is captured by trusted initialization, never read from JSON.
  ;; It must find the real configured adapter/user and current policy; no fake
  ;; adapter event is presented to the resolver as evidence of a new message.
  (let [original (:authority state)
        current (resolve-current original)
        planner (:planner original)
        _ (when-not (and current
                         (= (select-keys original [:source :actor])
                            (select-keys current [:source :actor]))
                         (= (get-in original [:user :id]) (get-in current [:user :id]))
                         (= (get-in original [:yetibot-user :id])
                            (get-in current [:yetibot-user :id])))
            (denied!))
        raw-provider (resolve-planner-provider planner)
        _ (doseq [grant [original current]]
            (policy/authorize! (:toolset-policy grant) (:actor original)
                               raw-provider (:model planner)))
        provider (prepare-source-provider raw-provider (:model planner))
        producer (:producer original)
        current-producer (when producer
                           (if (= producer planner) provider (resolve-producer producer)))]
    (when-not (and (= (:signature planner) (execution/provider-signature provider))
                   (or (nil? producer)
                       (and current-producer
                            (= (:signature producer) (execution/provider-signature current-producer)))))
      (denied!))
    (doseq [grant [original current]]
      (policy/authorize! (:toolset-policy grant) (:actor original) provider (:model planner))
      (when producer
        (policy/authorize! (:toolset-policy grant) (:actor original) current-producer (:model producer))))
    {:original original :current (assoc current :generation-cap (:generation-cap state))
     :provider provider :model (:model planner)
     :tools (set/intersection (:tools original) (:tools current))}))

(defn- tool-key [tool]
  (execution/tool-key tool))

(defn- request-for [session resolved]
  (let [state @(:state session) grant (:original resolved)
        event (or (get-in resolved [:current :runtime-event]) (event-for grant))
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
                 :budget (:budget session) :event event :core-context (atom nil)
                 :durable-session session :scheduled? (:scheduled? state)
                 :generation-limit (:generation-cap state) :no-tools? (:no-tools? grant)
                 :text-only (atom (boolean (:no-tools? grant)))
                 :planner-provider (:provider resolved) :planner-model (:model resolved)
                 :producer (:producer grant) :provider-identities (:provider-identities grant)
                 :toolset-name (:toolset-name grant)
                 :toolset-policy (execution/intersect-policy (:toolset-policy grant)
                                                              (get-in resolved [:current :toolset-policy]))
                 :tool-ceiling (atom (:tools resolved))
                 :accepted-jobs (atom (get-in state [:loop :accepted-jobs] []))
                 :reauthorize-expression!
                 (fn []
                   (let [fresh (resolve-authority! (:resolve-current session) @(:state session))]
                     (when-not (= (identity-fields (:user event)) (get-in fresh [:current :user])) (denied!))))
                 :allowed-tool? #(contains? (:tools resolved) (tool-key %))
                 :authorize-tool!
                 (fn [tool]
                   (let [fresh (resolve-authority! (:resolve-current session) @(:state session))]
                     (when-not (contains? (:tools fresh) (tool-key tool)) (denied!))))
                 :stage-timings (atom (get-in state [:loop :stage-timings] {}))
                 :planning-responses (atom (get-in state [:loop :planning-responses] 0))
                 :dataset-store store :dataset-owner owner :resume-loop (when (= :loop (:phase state)) (:loop state))
                 :authorize-inference!
                 (fn [attrs]
                   (let [fresh (resolve-authority! (:resolve-current session) @(:state session))]
                     (authorize-inference! grant (:current fresh) attrs)))
                 :admit! (fn [kind parent] (runs/admit! session kind parent))
                 :finish! #(runs/finish! session %1 %2)}]
    (assoc request :checkpoint-loop!
           (fn [loop-state handle]
             (runs/checkpoint! session
               (assoc @(:state session) :phase :loop :loop loop-state
                      :datasets (datasets/export-store! store owner)) handle)))))

(defonce ^:private live-workers (atom {}))

(defn- schedule-worker! [database row schedule resume]
  (let [key [database (db/qualified-table-name "agent_run") (:id row)]
        token (Object.)
        owned? #(identical? token (get-in @live-workers [key :token]))
        release! (fn []
                   (locking live-workers
                     (when (owned?) (swap! live-workers dissoc key))))
        acquired? (locking live-workers
                    (when-not (contains? @live-workers key)
                      (swap! live-workers assoc key {:token token :phase :queued}) true))]
    (when acquired?
      (try
        (schedule
          (fn []
            (if (locking live-workers
                  (when (and (owned?) (= :queued (get-in @live-workers [key :phase])))
                    (swap! live-workers assoc-in [key :phase] :running) true))
              (try (resume row) (finally (release!)))
              {:status :not-owner})))
        (catch Throwable _
          ;; A scheduler may enqueue and then throw, or throw after starting the
          ;; worker. Invalidate only queued work; a running owner exits itself.
          (locking live-workers
            (when (and (owned?) (= :queued (get-in @live-workers [key :phase])))
              (swap! live-workers dissoc key))))))))

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
            (let [providers (:providers (try (ai/config) (catch Exception _ {})))
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
                      session (assoc session :codec-config config :resolve-current resolve-current)
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
                                  origin/*origin* nil origin/*context* nil
                                  origin/*operation* nil origin/*scope* nil
                                  chat/*target* (get-in state [:authority :delivery :target])
                                  chat/*thread-ts* (get-in state [:authority :delivery :thread-ts])
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
                  _ (resolve-authority! resolve-current {:authority grant :generation-cap (:generation-limit request)})
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
              (schedule-worker! database row schedule resume!)
              receipt))]
    {:start start! :resume resume! :schedule schedule
     :schedule-row #(schedule-worker! database % schedule resume!)
     :restore-cron
     (fn [row]
       (when-not (= ledger/inherited-kind (:execution-origin row)) (denied!))
       (let [session (runs/claimed-cron-session database row (codec-config!))]
         (when-not (= :claimed (:status session)) (denied!))
         (runs/cron-snapshot @(:row session) (:codec-config session))
         ;; The actual scheduler owns tick admission/binding/outer completion.
         ;; Restoring this private grant is not evidence of a new adapter event.
         {:session session
          :request (request-for (assoc session :resolve-current resolve-current)
                     (resolve-authority! resolve-current @(:state session)))}))}))

(defn- current-authority
  "Resolve registered adapter and current users; the saved DTO is only a key."
  [grant]
  (let [source (:source grant)
        adapter (get @adapters/adapters (:uuid source))
        user (users/get-user source (get-in grant [:user :id]))
        bot (if (= :discord (:adapter source))
              (some-> adapter :yetibot-user deref)
              (users/get-user source (get-in grant [:yetibot-user :id])))
        selected (toolsets/select (mcp/config) (:toolset-name grant))
        tools (set (for [[server name] (:tools grant)
                         :when (cond (= "host" server) (and (= name "stage_changes") (= "github-pr" (get-in selected [:toolset :kind])))
                               (= "yetibot" server) (some #{name} (native/names selected))
                               :else
                                 (some #(and (= server (:name %))
                                             (or (not (contains? % :tools))
                                                 (some #{name} (:tools %))))
                                       (:servers selected)))]
                     [server name]))]
    (when-not (and adapter (adapters/connected? adapter)
                   (= (name (:adapter source))
                      (string/lower-case (adapters/platform-name adapter)))
                   user bot (not (:yetibot? user))
                   (= (:id user) (get-in grant [:actor :user]))
                   (every? (fn [[name identity]]
                             (= identity
                                (some #(when (= name (:name %))
                                         (select-keys % [:name :url :command :args]))
                                      (:servers selected))))
                           (:server-identities grant))
                   (every? (fn [[name _]]
                             (or (= "yetibot" name) (= "host" name)
                                 (contains? (:server-identities grant) name)))
                           (:tools grant)))
      (denied!))
    (assoc grant :user (identity-fields user) :yetibot-user (identity-fields bot)
           :toolset-policy (:toolset selected) :tools tools
           :runtime-event {:chat-source source :user user :yetibot-user bot
                           :event-type :message})))

(defn- reauthorize-source! [request]
  (let [grant (authority request)
        fresh (resolve-authority! current-authority
                {:authority grant :generation-cap (:generation-limit request)})]
    (when-not (= (identity-fields (get-in request [:event :user]))
                 (get-in fresh [:current :user])) (denied!))
    fresh))

(defn- deliver-saved! [{:keys [source target thread-ts]} result]
  (let [adapter (get @adapters/adapters (:uuid source))]
    (when-not (and adapter (adapters/connected? adapter)) (denied!))
    (binding [chat/*adapter* adapter chat/*adapter-uuid* (:uuid source)
              chat/*target* target chat/*thread-ts* thread-ts
              interpreter/*chat-source* source]
      ;; Adapter returns do not confirm complete delivery.
      (chat/send-msg (:result/value result)))))

(defn- retained-artifact? [artifact]
  (let [stored (get @images/image-store (:id artifact))]
    (and stored (= (:mime-type artifact) (:mime-type stored))
         (= (str (gemini/yetibot-base-url) "/generated-images/"
                 (:id artifact) "." (:format artifact)) (:url artifact)))))

(defn- human-literal? []
  (let [event execution/*event*
        {:keys [text ast]} (origin/root-template)
        body (:body event)
        extracted (when (string? body) (second (command/extract-command body)))
        raw (get-in event [:chat-source :raw-event])
        platform-body (case (get-in event [:chat-source :adapter])
                        :slack (:text raw) :discord (:content raw) nil)]
    (and (policy/actor event) (= body platform-body) extracted
         (= text extracted) (= ast (parser/parser extracted))
         (not execution/*command-capture*))))

(defn- command-origin [{:keys [text input]}]
  ;; Only leading options are controls. Contributions to the prompt are data.
  (let [tokens (re-matcher #"\S+" text)
        spans (loop [out []]
                (if (.find tokens)
                  (recur (conj out [(.group tokens) (.start tokens) (.end tokens)])) out))]
    (when (#{"ai" "cf" "zen"} (:cmd input))
     (loop [parts (next spans)]
      (when-let [[token start end] (first parts)]
        (when (string/starts-with? token "--")
          (or (origin/contributed-origin text start end)
              (when (not (#{"--no-tools" "--"} token))
                (when-let [[_ a b] (second parts)]
                  (origin/contributed-origin text a b)))
              (when-not (= "--" token)
                (recur (if (= "--no-tools" token) (next parts) (nnext parts)))))))))))

(declare runtime)

(defn- request-context [request]
  (letfn [(run [f]
            (let [request (if (= (:family-id request) (:family-id execution/*request*))
                            execution/*request* (or (some-> request :routing-request deref) request))]
              (binding [execution/*event* (:event request)
                        execution/*direct-input* nil execution/*direct-command* nil
                        execution/*command-capture* true]
                (if-let [scheduled (and (:scheduled? request)
                                         (some-> (find-ns 'yetibot.core.commands.cron)
                                                 (ns-resolve '*scheduled?*)))]
                  (with-bindings {scheduled true} (execution/with-request* request f))
                  (execution/with-request* request f)))))]
    (origin/context
      {:generation-limit #(run (fn [] (execution/require-open!)
                                 (:generation-limit execution/*request*)))
       :with-request run
       :start (fn [input]
                (run #(let [receipt ((:start @runtime) input)]
                        {:result/value
                         (str "Inherited work " (name (:status receipt))
                              "; job " (:job-id receipt) ". Provider "
                              (:provider receipt) " / " (:model receipt)
                              "; allocated " (:allocated receipt)
                              ", admitted " (:admitted receipt) ". Not completed.")
                         :result/data {:agent-job receipt}})))
       :execute-command
       (fn [input]
         (run #(let [_ (when-not (contains? @(:tool-ceiling execution/*request*)
                                             ["yetibot" "command"]) (denied!))
                     event execution/*event*
                     capture (commands/execute! (second (:match input))
                               {:chat-source (:chat-source event) :user (:user event)
                                :yetibot-user (:yetibot-user event)
                                :actor (policy/actor event)
                                :toolset (:toolset-policy execution/*request*)
                                :before-expression! (fn [& _] (execution/require-live!))})]
                 {:result/value (get-in capture [:pipeline :value])
                  :result/data (get-in capture [:pipeline :data])})))
       :admit-inference #(run (fn []
                               ((:authorize-inference! execution/*request*) %)
                               (execution/admit-operation! :inference)))
       :admit-command (fn [_] (run #(execution/admit-operation! :command)))
       :finish-operation (fn [handle outcome]
                           (run #(execution/finish-operation! handle outcome)))})))

(def ^:dynamic *command-source* nil)

(defn- alias-routing? [input]
  (let [[_ commands] (hooks/find-sub-cmds (:cmd input))
        [_ handler] (hooks/match-sub-cmds (:args input) commands)]
    ;; An alias is only evaluator routing. Its every expanded leaf is checked
    ;; again, including when core reuses the enclosing command admission.
    (= (class handler) (class (#'aliases/build-alias-cmd-fn "")))))

(defn- authorize-expression! [input]
  (execution/require-effects!)
  (if origin/*operation* (execution/require-live!) (execution/require-open!))
  (when-let [reauthorize (:reauthorize-expression! execution/*request*)] (reauthorize))
  (when-not (or (contains? @(:tool-ceiling execution/*request*) ["yetibot" "command"])
                (= "ai" (:cmd input))
                (and (= "agent" (:cmd input))
                     (not (re-find #"^run(?:\s|$)" (:args input))))
                (alias-routing? input))
    (denied!)))

(defn- with-command-request [callback text input f]
  (if-let [context (origin/dispatch-origin text input)]
    (origin/invoke! context :with-request
      #(do (authorize-expression! input) (callback text input f)))
    (binding [*command-source* (atom nil)]
      (let [result (callback text input f)]
        (if-let [context @*command-source*] (origin/mark-output! result context) result)))))

(defn- reauthorize-text-source! [request attrs]
  ;; This admits only legacy unauthenticated plaintext IO. It issues no actor,
  ;; command, durable, native-tool or adapter authority.
  (let [provider (:planner-provider request)
        model (:planner-model request)
        raw-current (resolve-planner-provider (planner-identity provider model))
        _ (doseq [t [(:toolset-policy request)
                      (:toolset (toolsets/select (mcp/config) (:toolset-name request)))]]
            (policy/authorize! t nil raw-current model))
        current (prepare-source-provider raw-current model)]
    (when-not (and (:untrusted-text? request)
                   (nil? execution/*event*) (nil? (:event request))
                   (= #{} @(:tool-ceiling request))
                   (= "text" (:operation attrs))
                   (= (:name provider) (:provider attrs)) (= model (:model attrs))
                   (= (execution/provider-signature provider)
                      (execution/provider-signature current)))
      (denied!))
    (doseq [t [(:toolset-policy request)
               (:toolset (toolsets/select (mcp/config) (:toolset-name request)))]]
      (policy/authorize! t nil current model))))

(defn- authorize-source-request [request selected provider model]
  (assoc request
                    :reauthorize-expression!
                    #(reauthorize-source! request)
                    :authorize-tool!
                    (fn [tool]
                      (reauthorize-source! request)
                      (let [current (toolsets/select (mcp/config) (:toolset-name request))
                            ceiling @(:tool-ceiling request)
                            key (tool-key tool)]
                        (when-not
                          (and ceiling (contains? ceiling key)
                               (cond (:host-stage? tool) (and (= "stage_changes" (:name tool)) (= "github-pr" (get-in current [:toolset :kind])))
                                 (:native? tool) (some #{(:name tool)} (native/names current))
                                 :else
                                 (let [name (first key)
                                       before (some #(when (= name (:name %)) %) (:servers selected))
                                       now (some #(when (= name (:name %)) %) (:servers current))]
                                   (and before now
                                        (= (select-keys before [:name :url :command :args])
                                           (select-keys now [:name :url :command :args]))
                                        (or (nil? (:tools now))
                                            (some #{(:name tool)} (:tools now)))))))
                          (denied!))
                        (policy/authorize! (:toolset current) (policy/actor execution/*event*)
                                           provider model)))
                    :authorize-inference!
                    (fn [attrs]
                      (if (:untrusted-text? request)
                        (reauthorize-text-source! request attrs)
                        (reauthorize-source! request))
                      (authorize-inference! {:toolset-policy (:toolset-policy request)
                                             :provider-identities (:provider-identities request)
                                             :actor (policy/actor execution/*event*)}
                                            {:toolset-policy
                                             (:toolset (toolsets/select (mcp/config) (:toolset-name request)))
                                             :actor (policy/actor execution/*event*)
                                             :generation-cap (:generation-limit request)} attrs))))

(def ^:private ^:dynamic *route-slot* nil)

(defn- source! [prepared f]
  (if (and (nil? execution/*request*) (origin/value-origin))
    (origin/invoke! (origin/value-origin) :with-request
      #(do (when-not execution/*request* (denied!)) (source! prepared f)))
    (if execution/*request*
    (do
      (execution/require-open!)
      (let [current (toolsets/select (mcp/config) (:toolset-name execution/*request*))
            actor (policy/actor execution/*event*)]
        (doseq [t [(:toolset-policy execution/*request*) (:toolset current)]]
          (policy/authorize! t actor (:provider prepared) (:model prepared))))
      (when (and (or (execution/text-only?) (:no-host-execution? execution/*request*))
                 (not (:producer-operation? prepared))
                 (or (not= (:model prepared) (:planner-model execution/*request*))
                     (not (or (= (execution/provider-signature (:provider prepared))
                                 (execution/provider-signature (:planner-provider execution/*request*)))
                              ;; The chat hook sees the deliberate no-tools wire
                              ;; projection, not a replacement planner identity.
                              (and (execution/text-only?) (:single-hop? prepared)
                                   (not (seq (:native-tools (:provider prepared))))
                                   (= (execution/provider-signature (dissoc (:provider prepared) :native-tools))
                                      (execution/provider-signature (dissoc (:planner-provider execution/*request*)
                                                                            :native-tools))))))))
        (denied!))
      (when (and (:no-tools prepared) (not (:single-hop? prepared))
                 (not (:producer-operation? prepared)))
        (reset! (:tool-ceiling execution/*request*) #{})
        (when-let [text-only (:text-only execution/*request*)] (reset! text-only true)))
      (f))
    (let [selected (toolsets/select (mcp/config) (:toolset prepared))
          actor (policy/actor execution/*event*)
          _ (policy/authorize! (:toolset selected) actor (:provider prepared) (:model prepared))
          producer-provider (prepare-source-provider (:provider prepared) (:model prepared))
          provider (if (:non-chat? prepared) (ai/provider (ai/config) nil) producer-provider)
          model (if (:non-chat? prepared)
                  (ai/default-model provider #(policy/model-allowed? (:toolset selected) actor provider %))
                  (:model prepared))
          _ (when-not (ai/nonblank-string? model) (denied!))
          _ (policy/authorize! (:toolset selected) actor provider model)
          provider (if (:non-chat? prepared) (prepare-source-provider provider model) provider)
          producer (cond-> (planner-identity producer-provider (:model prepared))
                     (:producer-kind prepared) (assoc :transport (:producer-kind prepared)))
          budgets (limits/effective prepared)
          request (merge (execution/family (:max-tool-calls budgets)
                                           (:max-output-tokens budgets))
                         {:event execution/*event* :routing-request *route-slot* :planner-provider provider
                          :planner-model model :producer producer :provider-identities (provider-identities)
                          :toolset-name (get-in selected [:toolset :name])
                          :toolset-policy (:toolset selected) :no-tools? (boolean (:no-tools prepared))
                          :untrusted-text? (and (nil? execution/*event*)
                                                (not (:producer-operation? prepared)))
                          :no-host-execution? (nil? execution/*event*)
                          :tool-ceiling (atom (when (or (:no-tools prepared) (nil? execution/*event*)) #{}))
                          :text-only (atom (boolean (:no-tools prepared)))
                          :stage-timings (atom {}) :planning-responses (atom 0)})
          request (authorize-source-request request selected provider model)]
      (policy/authorize! (:toolset-policy request) (policy/actor execution/*event*)
                         provider model)
      (try
        (execution/with-request* request
          #(do
             (when *command-source* (reset! *command-source* origin/*context*))
             (origin/mark-output! (f) origin/*context*)))
        (finally
          (when-not origin/*scope* (execution/close-family! request))))))))


(defn- route-source! [prepared args f]
  ;; This callback is installed by the host, never restored from a job DTO.
  (when (or execution/*request* (:no-tools prepared) (:toolset prepared)
            (nil? (execution/direct-request-key args))) (denied!))
  (let [config (mcp/config) router (:intent-router (ai/config))
        branches {:answer (toolsets/select config nil)
                  :code-change (toolsets/select config (:github-toolset router))}
        actor (policy/actor execution/*event*)
        classifier {:name "cloudflare" :url (cloudflare/account-url (:account-id router))}
        check! (fn [selected]
                 (let [original (:toolset selected)
                       current (:toolset (toolsets/select (mcp/config) (:name original)))]
                   (doseq [t [original current]]
                     (policy/authorize! t actor (:provider prepared) (:model prepared))
                     (policy/authorize! t actor classifier "@cf/cloudflare/clef"))))
        frozen (atom nil) slot (atom nil)
        key (execution/direct-request-key args)]
    (when-not (and (clef/enabled? router)
                   (= "github-pr" (get-in branches [:code-change :toolset :kind]))) (denied!))
    (doseq [selected (vals branches)] (check! selected))
    (binding [*route-slot* slot]
      (source! prepared
        (fn []
          (let [base execution/*request*
                pending (assoc base :routing-pending? true :tool-ceiling (atom #{})
                           :authorize-inference!
                           (fn [attrs]
                             (when-not (and (nil? @frozen)
                                            (= "cloudflare" (:provider attrs))
                                            (= "@cf/cloudflare/clef" (:model attrs))) (denied!))
                             (doseq [selected (vals branches)] (check! selected))
                             ((:authorize-inference! base) attrs)))
                freeze! (fn [branch continuation]
                          (execution/with-request* base execution/require-live!)
                          (when-not (and (contains? branches branch)
                                         (compare-and-set! frozen nil branch)) (denied!))
                          (let [selected (get branches branch) t (:toolset selected)
                                _ (check! selected)
                                request (authorize-source-request
                                          (assoc base :toolset-name (:name t) :toolset-policy t)
                                          selected (:planner-provider base) (:planner-model base))
                                request
                                (if (not= branch :code-change) request
                                  (assoc request :authorize-github!
                                    (fn [{:keys [proposal server tool ancestry?]}]
                                      (execution/require-effects!)
                                      (execution/require-open!)
                                      (reauthorize-source! request)
                                      (check! selected)
                                      (let [now (mcp/config)
                                            current (:toolset (toolsets/select now (:name t)))
                                            server-name (:name server)
                                            before (some #(when (= server-name (:name %)) %) (:servers config))
                                            after (some #(when (= server-name (:name %)) %) (:servers now))]
                                        (when-not
                                          (and (= router (:intent-router (ai/config))) (= t current)
                                               (= :executing (:status proposal))
                                               (= :automatic (:execution proposal)) (= key (:request-key proposal))
                                               (= t (:toolset proposal))
                                               (some #{(str (:owner proposal) "/" (:repo proposal))} (:repositories t))
                                               (or ancestry?
                                                 (and before after
                                                      (= (select-keys before [:name :url :command :args])
                                                         (select-keys after [:name :url :command :args]))
                                                      (= (select-keys server [:name :url :command :args])
                                                         (select-keys after [:name :url :command :args]))
                                                      (if (= server-name (:write-server t))
                                                        (some #{tool} ["create_branch" "push_files" "create_pull_request"])
                                                        (some #(and (= server-name (:name %))
                                                                    (or (nil? (:tools %)) (some #{tool} (:tools %))))
                                                              (:servers t))))))
                                          (denied!))))))]
                            (reset! slot request)
                            (execution/with-request* request continuation)))]
            (reset! slot pending)
            (execution/with-request* pending #(f freeze!))))))))

(defn- with-sdk-transport* [provider model options f]
  ;; Only the internal host-code chat hook enters here. No option, provider
  ;; field, actor event, or serialized Context can issue execution authority.
  ;; SDK records can retain unused dialect defaults (for example Responses
  ;; native fields on a Chat Completions turn); validate actual wire identity,
  ;; not the stricter operator configuration schema.
  (when-not (and (ai/nonblank-string? (:name provider)) (ai/base-url? (:url provider))
                 (or (nil? (:api provider)) (#{"chat-completions" "responses"} (:api provider)))
                 (ai/nonblank-string? model)) (denied!))
  (let [selected (toolsets/select (mcp/config) nil)
        original (:toolset selected)
        configured (some #(when (= (:name provider) (:name %)) %) (:providers (ai/grok-config)))
        _ (when (and configured
                     (not= (execution/provider-signature provider)
                           (execution/provider-signature configured))) (denied!))
        _ (policy/authorize! original nil provider model)
        budgets (limits/effective options)
        upstream-admit usage/*admit-inference*
        upstream-finish usage/*finish-inference*
        wire-provider (atom nil)
        request-slot (atom nil)
        request (assoc (execution/family (:max-tool-calls budgets) (:max-output-tokens budgets))
                       :event nil :no-host-execution? true :routing-request request-slot
                       :planner-provider provider :planner-model model
                       :toolset-name (:name original) :toolset-policy original
                       :tool-ceiling (atom #{}))
        request (assoc request
                  :reauthorize-expression! denied!
                  :authorize-tool! (fn [_] (denied!))
                  :authorize-inference!
                  (fn [attrs]
                    (execution/require-open!)
                    (let [now (some #(when (= (:name provider) (:name %)) %) (:providers (ai/grok-config)))
                          current (:toolset (toolsets/select (mcp/config) (:name original)))
                          prepared @wire-provider
                          cap (:output-cap attrs)]
                      (when-not (and prepared (= "text" (:operation attrs))
                                     (= (:name provider) (:provider attrs)) (= model (:model attrs))
                                     (= (execution/provider-signature prepared) (:provider-signature attrs))
                                     (= (str (string/replace (:url prepared) #"/+$" "")
                                             (if (ai/responses? prepared) "/responses" "/chat/completions"))
                                        (:endpoint attrs))
                                     (integer? cap) (<= 1 cap (:generation-limit request))
                                     ;; Once configured, revocation is never absence.
                                     (if configured
                                       (and now (= (execution/provider-signature configured)
                                                   (execution/provider-signature now)))
                                       (or (nil? now)
                                           (= (execution/provider-signature provider)
                                              (execution/provider-signature now)))))
                        (denied!))
                      (doseq [policy [original current]]
                        (policy/authorize! policy nil prepared model)))))]
    (try
      (execution/with-request* request
        #(let [_ (execution/require-open!)
               prepared (ai/prepare-provider provider model)
               _ (reset! wire-provider prepared)
               live-request (assoc request :planner-provider prepared)
               _ (reset! request-slot live-request)]
           (execution/with-request* live-request
             (fn []
               (let [admit usage/*admit-inference* finish usage/*finish-inference*]
                 (binding [execution/*event* nil
                           usage/*admit-inference*
                           (fn [attrs]
                             (let [handle (admit attrs)]
                               (cond-> handle
                                 upstream-admit (assoc ::upstream (upstream-admit attrs)))))
                           usage/*finish-inference*
                           (fn [handle outcome]
                             (when (and upstream-finish (::upstream handle))
                               (upstream-finish (::upstream handle) outcome))
                             (finish handle outcome))]
                   (origin/mark-output! (f prepared) origin/*context*)))))))
      (finally
        (execution/close-family! request)
        (when-let [context @(:core-context request)] (origin/close! context))))))

(defn- source-chat [callback provider model messages options]
  (cond
    execution/*request*
    (execution/with-source* {:provider provider :model model :single-hop? true}
      #(case (:continuation-transport provider)
         "xai-text" (let [result (xai/generate-text messages)]
                      {:status "completed" :output [{:type "message" :content [{:type "output_text" :text (:text result)}]}]})
         "openai-text" (:body ((ns-resolve 'yetibot.commands.openai 'openai-completions) messages))
         (callback provider model messages options)))

    (origin/value-origin)
    (origin/invoke! (origin/value-origin) :with-request
      #(do (when-not execution/*request* (denied!))
           (source-chat callback provider model messages options)))

    :else
    (with-sdk-transport* provider model options #(callback % model messages options))))

(defn- source-complete [callback provider model messages & [options]]
  (let [options (or options {})]
    (cond
      execution/*request*
      (execution/with-source* {:provider provider :model model :single-hop? true}
        #(callback provider model messages options))
      (origin/value-origin)
      (origin/invoke! (origin/value-origin) :with-request
        #(do (when-not execution/*request* (denied!))
             (source-complete callback provider model messages options)))
      :else (with-sdk-transport* provider model options
              #(callback % model messages options)))))

(defn- source-xai-text [callback prompt]
  (execution/with-source* {:provider (native-text-provider "xai-text")
                           :model "grok-4.7" :no-tools true :producer-operation? true}
    #(callback prompt)))

(defn- source-xai-image [callback & args]
  (execution/with-source* {:provider {:name "xai" :url "https://api.x.ai/v1"}
                           :model "grok-imagine-image-2.0" :no-tools true :non-chat? true :producer-kind "xai-image"
                           :producer-operation? true}
    #(apply callback args)))

(defn- source-gemini-image [callback & args]
  (execution/with-source* {:provider {:name "gemini"
                                     :url "https://generativelanguage.googleapis.com/v1beta"}
                           :model (gemini/gemini-model) :no-tools true :non-chat? true :producer-kind "gemini-image"
                           :producer-operation? true}
    #(apply callback args)))

(defn- source-gemini-video [callback & args]
  (execution/with-source* {:provider {:name "gemini"
                                     :url "https://generativelanguage.googleapis.com/v1beta"}
                           :model (or (nth args 2 nil) (gemini/veo-model)) :no-tools true :non-chat? true :producer-kind "gemini-video"
                           :producer-operation? true}
    #(apply callback args)))

(defn- source-structured [callback config token model payload]
  (execution/with-source* {:provider {:name "cloudflare"
                                     :url (cloudflare/account-url (:account-id config))}
                           :model (str "@cf/cloudflare/" model) :no-tools true :non-chat? true :producer-kind "cloudflare-classifier"
                           :producer-operation? true}
    #(callback config token model payload)))

(defn- source-classify [callback config text]
  (execution/with-source* {:provider {:name "cloudflare"
                                     :url (cloudflare/account-url (:account-id config))}
                           :model "@cf/cloudflare/clef" :no-tools true :non-chat? true :producer-kind "cloudflare-classifier"
                           :producer-operation? true}
    #(callback config text)))

(defonce ^:private runtime (atom nil))


(def ^:dynamic *cron-transfer* nil)

(defn- with-cron-write [callback & args]
  (let [request execution/*request*]
    (if (or *cron-transfer* (not (:metered? request)))
      (apply callback args)
      (locking (:budget request)
        (let [pending (atom nil)]
          (binding [*cron-transfer* pending]
            (try
              (let [result (apply callback args)]
                (when @pending
                  (if (:isError result)
                    (swap! (:budget request) assoc :closed? true :unknown? true)
                    (if-let [session (:durable-session request)]
                      (let [row (ledger/read-row (:database session)
                                                (get-in session [:owner :table])
                                                (get-in session [:owner :id]))]
                        (reset! (:row session) row)
                        (swap! (:budget request) merge (runs/counters row)))
                      (swap! (:budget request) update :reserved + @pending))))
                result)
              (catch Throwable e
                ;; Lost commit acknowledgement: seal, never refund/retry.
                (when @pending
                  (swap! (:budget request) assoc :closed? true :unknown? true))
                (throw e)))))))))

(defn- write-cron! [tx existing entity context]
  (let [request execution/*request*
        config (mcp/config)]
    (cond
      (= :inherited (cron/origin-kind existing))
      (let [_ (runs/cron-snapshot existing config)
            ;; Edits retain delivery intent as well as core's sticky fence.
            state (runs/decode (:execution-state existing) config)
            incoming (when request (authority request))
            state (if incoming
                    (-> state
                        (update :generation-cap min (:generation-limit request))
                        (update-in [:authority :tools] set/intersection (:tools incoming))
                        (update-in [:authority :toolset-policy]
                                   execution/intersect-policy (:toolset-policy incoming))) state)]
        ;; Core's sticky write retains counters, fence, phase and original actor.
        (merge entity (select-keys existing
                         (conj (mapv first ledger/execution-columns) :execution-origin :user-id))
               {:execution-state (runs/encode state config)}))

      context
      (do
        (when-not (and (:metered? request) *cron-transfer*) (denied!))
        (when (or (:closed? @(:budget request)) (:unknown? @(:budget request))) (denied!))
        (let [grant (authority request)
              state (runs/cron-state
                      {:job-id (str (java.util.UUID/randomUUID))
                       :family-id (:family-id request)
                       :parent-id (some-> request :durable-session :state deref :job-id)
                       :authority grant :generation-cap (:generation-limit request)
                       :phase :queued})
              text (runs/encode state config)
              attrs (if-let [session (:durable-session request)]
                      (cron/inherit-attrs! tx (:owner session) existing
                                          (assoc entity :execution-state text))
                      (merge entity
                             (ledger/allocation-attrs
                               {:limit (execution/remaining)
                                :generation-cap (:generation-limit request)})
                             {:execution-origin ledger/inherited-kind :execution-state text}))]
          (when @*cron-transfer* (denied!))
          (reset! *cron-transfer* (:execution-limit attrs))
          attrs))

      (human-literal?) (assoc entity :execution-origin "human-literal-v1")
      :else (assoc entity :execution-origin "untrusted-v1"))))

(defn- tick! [snapshot]
  (let [database (:url (db/config))
        claim (jdbc/with-db-transaction [tx database]
                (let [claim (cron/claim-tick! tx (:id snapshot))]
                  (if (= :claimed (:status claim))
                    (merge (ledger/read-row tx "cron" (:id snapshot))
                           (select-keys claim [:status :owner :operation :state-revision]))
                    claim)))]
    (if (not= :claimed (:status claim)) (select-keys claim [:status])
      (let [{:keys [session request]} ((:restore-cron @runtime) claim)
            event (:event request)
            operation (:operation claim)]
        (try
          (binding [execution/*event* event execution/*command-capture* true
                    execution/*direct-input* nil execution/*direct-command* nil
                    execution/*sensitive* (atom true)
                    interpreter/*chat-source* (:chat-source event)
                    interpreter/*current-user* (:user event)
                    interpreter/*yetibot-user* (:yetibot-user event)
                    chat/*adapter-uuid* (get-in event [:chat-source :uuid])
                    chat/*adapter* (get @adapters/adapters (get-in event [:chat-source :uuid]))
                    chat/*target* (get-in @(:state session) [:authority :delivery :target])
                    chat/*thread-ts* (get-in @(:state session) [:authority :delivery :thread-ts])
                    origin/*operation* operation]
            (execution/with-request* request
              (fn []
                (let [result (handler/handle-parsed-expr
                               (:chat-source event) (:user event) (:yetibot-user event)
                               (parser/parser (:cmd @(:row session))))]
                  ;; Deliver inside the tick barrier. Adapter ambiguity never
                  ;; makes the saved expression replayable.
                  (runs/begin-cron-delivery! session operation)
                  (chat/chat-data-structure (:value result))
                  (runs/finish! session operation {:state :unknown})
                  {:status :unconfirmed}))))
          (catch Throwable e
            (try (runs/finish! session operation {:state :unknown})
                 (catch Throwable _ nil))
            (throw e))
          (finally
            (execution/close-family! request)
            (datasets/close! (:dataset-store request) (:dataset-owner request))))))))

(defn- observe-event! [event]
  (when (policy/actor event)
    ;; Only authenticated gateway events refresh Discord's current user record.
    (when (= :discord (get-in event [:chat-source :adapter]))
      (users/add-user-without-channel :discord (:user event)))
    (try
      (let [database (:url (db/config))
            table (db/qualified-table-name "agent_run")
            rows (jdbc/query database
                   [(str "SELECT * FROM " table
                         " WHERE execution_kind=? AND execution_phase IN ('queued','ready','operation-in-flight')")
                    ledger/inherited-kind]
                   {:identifiers #(string/replace % "_" "-")})]
        (doseq [row rows]
          (try
            (let [state (runs/decode (:execution-state row) (mcp/config))]
              (current-authority (:authority state))
              ((:schedule-row @runtime) row))
            ;; One invalid/unavailable grant must not starve unrelated jobs.
            (catch Exception _ nil))))
      ;; DB/adapter startup is not an authenticated caller or a CLI fallback.
      (catch Exception _ nil))))

(defn install!
  "Install one reload-safe host. Loading never runs inference or starts workers."
  []
  (reset! runtime (callbacks {:database (:url (db/config))
                             :resolve-current current-authority
                             :deliver deliver-saved!
                             :artifact-valid? retained-artifact?}))
  (origin/install-host! ::host
    {:source source! :route-source route-source! :request-context request-context :observe-event observe-event!
     :write-cron write-cron! :tick tick!
     :command-origin command-origin
     :classify-agent (fn [_ _] (when (human-literal?) :human-literal))
     :resume (fn [row] ((:schedule-row @runtime) row))})
  (hooke/add-hook #'origin/with-command* #'with-command-request)
  (hooke/add-hook #'cron/create #'with-cron-write)
  (hooke/add-hook #'cron/update-where #'with-cron-write)
  (hooke/add-hook #'cron-tools/call! #'with-cron-write)
  (hooke/add-hook #'ai/chat #'source-chat)
  (hooke/add-hook #'ai/complete #'source-complete)
  (hooke/add-hook #'xai/generate-text #'source-xai-text)
  (hooke/add-hook #'xai/generate-image #'source-xai-image)
  (hooke/add-hook #'gemini/generate-image #'source-gemini-image)
  (hooke/add-hook #'gemini/generate-video #'source-gemini-video)
  (hooke/add-hook #'cloudflare/infer! #'source-structured)
  (hooke/add-hook #'clef/classify! #'source-classify)
  nil)
