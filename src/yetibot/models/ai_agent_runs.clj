(ns yetibot.models.ai-agent-runs
  "Private checkpoint codec and app use of core's two transactional ledgers.
   No schema, callbacks, credentials or interpreter continuations are persisted."
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.db.agent-run :as ledger]
            [yetibot.core.db.util :as db]
            [yetibot.models.ai-input :as input])
  (:import [com.fasterxml.jackson.core JsonFactory JsonParser$Feature]))

(defn- invalid! []
  (throw (ex-info "Inherited checkpoint is unavailable or unsafe."
                  {:ai/error true :ai/request-not-sent true})))

(defn- encode-value [value config]
  (let [remaining (volatile! 2097152)
        redact (mcp/metadata-redactor config)]
    (letfn [(walk [v depth]
              (when (or (> depth 48) (neg? (vswap! remaining dec))) (invalid!))
              (cond
                (nil? v) nil
                (boolean? v) v
                (instance? java.math.BigDecimal v)
                (if (Double/isFinite (double v)) ["decimal" (str v)] (invalid!))
                (number? v) (if (and (not (ratio? v)) (Double/isFinite (double v))) v (invalid!))
                (string? v)
                (do (when (or (neg? (vswap! remaining - (count v)))
                              (not= v (redact v))) (invalid!)) v)
                (keyword? v) ["keyword" (subs (str v) 1)]
                (instance? java.util.UUID v) ["uuid" (str v)]
                (map? v)
                ["map" (mapv (fn [[k cell]]
                               (when (or (not (or (keyword? k) (string? k)))
                                         (input/sensitive-key? k)) (invalid!))
                               [(walk k (inc depth)) (walk cell (inc depth))]) v)]
                (set? v) ["set" (mapv #(walk % (inc depth)) v)]
                (vector? v) ["vector" (mapv #(walk % (inc depth)) v)]
                :else (invalid!)))]
      (walk value 0))))

(defn- decode-value [value]
  (if-not (vector? value)
    (if (or (nil? value) (boolean? value) (number? value) (string? value)) value (invalid!))
    (let [[tag data] value]
      (when-not (= 2 (count value)) (invalid!))
      (case tag
        "keyword" (if (and (string? data) (<= (count data) 128)) (keyword data) (invalid!))
        "decimal" (try (bigdec data) (catch Exception _ (invalid!)))
        "uuid" (try (java.util.UUID/fromString data) (catch Exception _ (invalid!)))
        "vector" (mapv decode-value data)
        "set" (let [items (mapv decode-value data) result (set items)]
                (when-not (= (count items) (count result)) (invalid!))
                result)
        "map" (let [pairs (mapv (fn [[k v :as pair]]
                                  (when-not (= 2 (count pair)) (invalid!))
                                  [(decode-value k) (decode-value v)]) data)
                    result (into {} pairs)]
                (when-not (= (count pairs) (count result)) (invalid!))
                result)
        (invalid!)))))

(def ^:private checkpoint-fields
  #{:job-id :family-id :parent-id :authority :generation-cap :phase :loop
    :datasets :result :work-outcome :scheduled? :usage-context-id})

(defn- validate-authority! [authority]
  (let [{:keys [source user yetibot-user actor tools planner delivery]} authority
        identity? (fn [identity transports]
                    (and (map? identity)
                         (every? #{:name :model :signature :transport} (keys identity))
                         (every? #(contains? identity %) [:name :model :signature])
                         (or (nil? (:transport identity)) (contains? transports (:transport identity)))
                         (every? #(and (string? %) (<= 1 (count %) 256)) (vals identity))))
        raw (:raw-event source)
        channel (if (= :slack (:adapter source)) (:channel raw) (:channel-id raw))
        uid (if (= :slack (:adapter source)) (:user raw) (get-in raw [:author :id]))]
    (when-not
      (and (map? authority)
           (every? #{:source :user :yetibot-user :actor :tools :planner :delivery
                     :toolset-name :toolset-policy :server-identities :no-tools? :producer :provider-identities} (keys authority))
           (#{:slack :discord} (:adapter source))
           (some? (:uuid source)) (string? (:room source))
           (every? #{:adapter :uuid :room :raw-event} (keys source))
           (every? #{:user :channel :ts :thread_ts :id :channel-id :author} (keys raw))
           (string? channel) (string? uid) (= uid (:id user))
           (every? #{:id :username :name :yetibot?} (keys user))
           (not (:yetibot? user)) (some? (:id yetibot-user))
           (every? #{:id :username :name :yetibot?} (keys yetibot-user))
           (= (name (:adapter source)) (:adapter actor))
           (= (str (:uuid source)) (:connection actor))
           (= (:id user) (:user actor))
           (= (str (:uuid source) "/" channel) (:conversation actor))
           (= #{:adapter :connection :user :conversation :thread} (set (keys actor)))
           (set? tools) (<= (count tools) 64)
           (every? #(and (vector? %) (= 2 (count %))
                         (every? (fn [s] (and (string? s) (<= 1 (count s) 256))) %)) tools)
           (identity? planner #{"xai-text" "openai-text"})
           (or (nil? (:producer authority))
               (identity? (:producer authority) #{"xai-text" "openai-text" "xai-image" "gemini-image"
                                                   "gemini-video" "cloudflare-classifier"}))
           (or (nil? (:provider-identities authority))
               (and (map? (:provider-identities authority)) (<= (count (:provider-identities authority)) 128)
                    (every? #(and (string? %) (<= 1 (count %) 300))
                            (concat (keys (:provider-identities authority)) (vals (:provider-identities authority))))))
           (or (not (contains? authority :no-tools?)) (boolean? (:no-tools? authority)))
           (or (not (:no-tools? authority)) (empty? tools))
           (or (nil? delivery)
               (and (= source (:source delivery)) (string? (:target delivery))
                    (every? #{:source :target :thread-ts} (keys delivery)))))
      (invalid!)))
  authority)

(defn- validate-loop! [phase loop-state]
  (when (or (= phase :queued) (= phase :loop))
    ;; Cron owners have no conversation. A worker with a conversation preserves
    ;; exact provider messages, including opaque Responses items, as data only.
    (when loop-state
      (when-not (and (map? loop-state)
                     (vector? (:messages loop-state))
                     (<= (count (:messages loop-state)) 1024)
                     (every? map? (:messages loop-state))
                     (every? #{:messages :used :seen :actions :stage :response :accounting
                               :correction? :body :trace :chart-terminal :chart-stop
                               :artifacts :errors :batch-start :batch :planning-responses :stage-timings :accepted-jobs} (keys loop-state)))
        (invalid!))
      (when (= phase :loop)
        (when-not (and (map? (:body loop-state)) (map? (:trace loop-state))
                       (set? (:seen loop-state)) (set? (:actions loop-state))
                       (integer? (:used loop-state)) (<= 0 (:used loop-state) 128)
                       (integer? (:response loop-state)) (<= 1 (:response loop-state) 129)
                       (#{:initial :follow-up} (:stage loop-state))
                       (boolean? (:correction? loop-state)))
          (invalid!)))))
  loop-state)

(defn- validate-checkpoint! [state]
  (when-not (and (map? state) (every? checkpoint-fields (keys state))
                 (every? #(and (string? %) (<= 1 (count %) 128))
                         [(:job-id state) (:family-id state)])
                 (map? (:authority state))
                 (integer? (:generation-cap state))
                 (<= 1 (:generation-cap state) 262144)
                 (#{:queued :loop :result-saved :delivery-started} (:phase state))
                 (not-any? #(contains? (:authority state) %)
                           [:direct-input :direct-command :credentials :key :key-file]))
    (invalid!))
  (validate-authority! (:authority state))
  (validate-loop! (:phase state) (:loop state))
  (when (#{:result-saved :delivery-started} (:phase state))
    (when-not (and (map? (:result state)) (string? (get-in state [:result :result/value]))
                   (#{:completed :partial :queued :exhausted} (:work-outcome state))
                   (not (:loop state)) (not (:datasets state))) (invalid!)))
  state)

(defn encode
  "Strict, versioned, data-only, credential-safe complete JSON <= two MiB.
   Metadata is deliberately not serialized; decoded maps confer no authority."
  [state config]
  (ledger/bounded-json
    (json/generate-string {"version" 1 "checkpoint"
                           (encode-value (validate-checkpoint! state) config)})))

(defn- bounded-document! [text]
  (ledger/bounded-json text)
  ;; Bound nesting before the materializing parser runs. Tagged map/vector
  ;; representation adds JSON levels beyond the bounded semantic data walk.
  (with-open [parser (.createParser (JsonFactory.) ^String text)]
    (.enable parser JsonParser$Feature/STRICT_DUPLICATE_DETECTION)
    (loop [depth 0]
      (when-let [token (.nextToken parser)]
        (let [depth (case (.name token)
                      ("START_OBJECT" "START_ARRAY") (inc depth)
                      ("END_OBJECT" "END_ARRAY") (dec depth)
                      depth)]
          (when (> depth 192) (invalid!))
          (recur depth))))))

(defn decode [text config]
  (try
    (bounded-document! text)
    (let [document (json/parse-string text)]
      (when-not (and (= #{"version" "checkpoint"} (set (keys document)))
                     (= 1 (get document "version"))) (invalid!))
      (let [state (validate-checkpoint! (decode-value (get document "checkpoint")))]
        ;; Revalidate both bounds and credential safety after parsing.
        (encode state config)
        state))
    (catch Exception _ (invalid!))))

(def ^:private grant-fields
  [:job-id :family-id :parent-id :authority :generation-cap :scheduled? :usage-context-id])

(defn- row-state! [row config]
  (let [state (decode (:execution-state row) config)]
    (when-not (and (<= (:generation-cap state) (:generation-cap row))
                   (or (nil? (:run-id row)) (= (:job-id state) (:run-id row)))) (invalid!))
    state))

(defn counters [row]
  {:limit (:execution-limit row) :used (:execution-used row)
   :reserved (:execution-reserved row) :closed? false})

(defn transfer-live!
  "Reserve all live parent's remainder only after definite commit. Any uncertain
   transaction seals it; recover the SAME job ID, never retry an allocation."
  [database request table entity state config]
  (let [budget (:budget request)]
    (locking budget
      (let [{:keys [limit used reserved closed? unknown?]} @budget
            available (- limit used (or reserved 0))
            _ (when (or closed? unknown? (neg? available)
                         (> (:generation-cap state) (:generation-limit request))) (invalid!))
            text (encode state config)]
        (try
          (let [row (jdbc/with-db-transaction [tx database]
                      (ledger/create-inherited! tx table
                        (assoc entity :execution-state text)
                        {:limit available :generation-cap (:generation-limit request)}))]
            (when-not (and (= available (:execution-limit row))
                           (or (not= table "agent_run")
                               (= (:run-id entity) (:run-id row)))) (invalid!))
            (swap! budget update :reserved (fnil + 0) available)
            row)
          (catch Throwable _
            (swap! budget assoc :reserved (- limit used) :closed? true
                   :uncertain-job-id (:job-id state))
            (invalid!)))))))

(defn transfer!
  "Durable agent/cron parent: child insert and reservation share caller tx."
  [tx owner table entity state config]
  (let [parent (ledger/lock-row! tx (:table owner) (:id owner))
        before (row-state! parent config)]
    (when-not (and (#{:queued :loop} (:phase before))
                   (<= (:generation-cap state) (:generation-cap before)))
      (invalid!))
    (ledger/transfer! tx owner table
      (assoc entity :execution-state (encode state config)))))

(defn- terminal! [tx owner row outcome]
  (let [done (ledger/checkpoint! tx owner (:state-revision row) nil "terminal")]
    ;; Typed inherited rows never contained a legacy caller prompt. Retain only
    ;; a closed human-readable summary, never parsed as state or authority.
    (when (= "agent_run" (:table owner))
      (when-not
        (= [1] (jdbc/update! tx (db/qualified-table-name "agent_run")
                 {:request (case outcome
                             :completed "Inherited result produced; delivery unconfirmed."
                             :exhausted "Inherited allowance exhausted; delivery unconfirmed."
                             :partial "Inherited work partial; delivery unconfirmed."
                             :queued "Inherited child work queued; delivery unconfirmed."
                             "Inherited work stopped; completion and delivery unconfirmed.")}
                 ["id=? AND execution_kind=?" (:id owner) ledger/inherited-kind]))
        (invalid!)))
    done))

(defn claim!
  "Private host worker entry, NOT a public restore/capability constructor.
   Interrupted delivery is terminal and cannot be turned into a runnable loop."
  [database table id revision config]
  (jdbc/with-db-transaction [tx database]
    (let [row (ledger/lock-row! tx table id)]
      (when-not (= ledger/inherited-kind (:execution-kind row)) (invalid!))
      (case (:execution-phase row)
        "terminal" {:status :terminal}
        "unknown" {:status :unknown}
        (let [state (row-state! row config)
              claimed (ledger/claim! tx table id revision)]
          (cond
            (= :unknown (:status claimed)) claimed
            (= :delivery-started (:phase state))
            (do (terminal! tx (:owner claimed) claimed (:work-outcome state))
                {:status :unknown})
            :else {:status :claimed :owner (:owner claimed)
                   :database database :row (atom claimed) :state (atom state)
                   :budget (atom (counters claimed)) :codec-config config}))))))

(defn- transaction! [session f]
  (locking (:budget session)
    (when (:closed? @(:budget session)) (invalid!))
    (let [row (jdbc/with-db-transaction [tx (:database session)]
                (let [row (ledger/lock-row! tx (get-in session [:owner :table])
                                          (get-in session [:owner :id]))]
                  (when-not (= (:state-revision @(:row session)) (:state-revision row))
                    (invalid!))
                  (f tx row)))]
      (reset! (:row session) row)
      (swap! (:budget session) merge (counters row))
      row)))

(defn admit! [session kind parent]
  (let [handle (atom nil)]
    (transaction! session
      (fn [tx row]
        (when-not (#{:queued :loop} (:phase (decode (:execution-state row)
                                                           (:codec-config session))))
          (invalid!))
        (reset! handle (ledger/admit! tx (:owner session) (:state-revision row)
                        {:kind (name kind) :parent-ordinal (:ordinal parent)}))
        (ledger/read-row tx (get-in session [:owner :table]) (get-in session [:owner :id]))))
    @handle))

(defn finish! [session handle outcome]
  (transaction! session
    (fn [tx _]
      (ledger/finish-operation! tx (:owner session) handle (:state outcome)))))

(defn checkpoint!
  "Only the outer loop owner commits its complete state and own tool receipt."
  [session state handle]
  (let [text (encode state (:codec-config session))]
    (transaction! session
      (fn [tx row]
        (let [before (row-state! row (:codec-config session))]
          (when-not (and (#{:queued :loop} (:phase before))
                         (= (select-keys before grant-fields) (select-keys state grant-fields)))
            (invalid!)))
        (if handle
          (ledger/complete-operation! tx (:owner session) handle text "ready")
          (ledger/checkpoint! tx (:owner session) (:state-revision row) text "ready"))))
    (reset! (:state session) state)))

(defn save-result! [session result outcome]
  (let [state (-> @(:state session)
                  (assoc :phase :result-saved :result result :work-outcome outcome)
                  (dissoc :loop :datasets))]
    (checkpoint! session state nil)))

(defn begin-delivery!
  "Fenced, locked CAS before send; a commit exception never permits IO.
   Delivery has no model/tool allowance charge. No outstanding receipt is cleared."
  [session]
  (let [state (assoc @(:state session) :phase :delivery-started)
        text (encode state (:codec-config session))]
    (transaction! session
      (fn [tx row]
        (when-not (= :result-saved (:phase (decode (:execution-state row)
                                                  (:codec-config session))))
          (invalid!))
        (ledger/checkpoint! tx (:owner session) (:state-revision row) text "ready")))
    (reset! (:state session) state)
    (:result state)))

(defn finish-delivery! [session]
  (transaction! session
    (fn [tx row]
      (let [state (decode (:execution-state row) (:codec-config session))]
        (when-not (= :delivery-started (:phase state)) (invalid!))
        (terminal! tx (:owner session) row (:work-outcome state))))))

(defn cron-grant
  "No transcript, dataset, adapter event or new allowance. Scheduler wiring owns
   the real cron row; this bounded payload carries only its original grant."
  [state]
  (select-keys (validate-checkpoint! state)
               [:job-id :family-id :parent-id :authority :generation-cap :scheduled?]))


(defn cron-state
  "Create a schedule's private grant, not an adapter invocation or transcript.
   Allocation/counters remain exclusively in the core cron row."
  [state]
  (assoc (cron-grant state) :phase :queued :scheduled? true))

(defn cron-snapshot [row config]
  (when-not (and (= ledger/inherited-kind (:execution-kind row))
                 (= ledger/inherited-kind (:execution-origin row))) (invalid!))
  (let [state (row-state! row config)]
    (when (or (:loop state) (:datasets state) (:result state)
              (not (:scheduled? state))) (invalid!))
    {:grant (cron-grant state)
     :counters (select-keys row [:execution-limit :execution-used :execution-reserved
                                :operation-ordinal :generation-cap :execution-phase
                                :state-revision :worker-fence])}))

(defn claimed-cron-session
  "Consume an already admitted core tick once. Re-read current counters, not
   claim-tick!'s pre-admission row fields, and verify the exact owner/revision."
  [database claim config]
  (jdbc/with-db-transaction [tx database]
    (let [row (ledger/lock-row! tx "cron" (:id claim))
          owner (:owner claim)
          operation (:operation claim)]
      (when-not (and (= :claimed (:status claim))
                     (= (:id row) (:id owner)) (= "cron" (:table owner))
                     (= (:worker-fence row) (:fence owner))
                     (= (:state-revision row) (:state-revision claim))
                     (= (:operation-ordinal row) (:ordinal operation))
                     (= "operation-in-flight" (:execution-phase row)))
        (invalid!))
      (cron-snapshot row config)
      {:status :claimed :owner owner :database database
       :row (atom row) :state (atom (row-state! row config))
       :budget (atom (counters row)) :codec-config config
       :operation operation})))
