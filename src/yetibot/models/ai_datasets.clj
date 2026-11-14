(ns yetibot.models.ai-datasets
  "Private, bounded datasets owned by one live host request. No global registry."
  (:require [cheshire.core :as json]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-input :as input])
  (:import [java.time Instant LocalDate OffsetDateTime]
           [java.util UUID]))

(def ^:private max-bytes (* 2 1024 1024))
(def ^:private descriptor-bytes (* 12 1024))

(defn- invalid! [code]
  (throw (ex-info "Dataset unavailable: invalid data, scope or resource bound."
                  {:ai/error true :dataset/code code})))

(defn- json-bytes [x]
  (alength (.getBytes ^String (json/generate-string x) "UTF-8")))

(defn- spelling [k]
  (cond (string? k) k
        (keyword? k) (subs (str k) 1)
        :else (invalid! :type)))

(defn- snapshot [value]
  ;; Charge while walking, before serialization, so infinite sequences and very
  ;; large strings cannot be materialized by the JSON writer. No prefix survives.
  (let [used (volatile! 0)]
    (letfn [(charge! [n]
              (when (> (vswap! used + n) max-bytes) (invalid! :size)))
            (walk [v depth]
              (when (> depth 12) (invalid! :depth))
              (charge! 1)
              (cond
                (or (nil? v) (boolean? v)) v
                (string? v) (do (when (> (count v) max-bytes) (invalid! :size))
                                (charge! (alength (.getBytes ^String v "UTF-8"))) v)
                (number? v)
                (if (and (not (ratio? v)) (Double/isFinite (double v)))
                  (do (charge! (count (str v))) v) (invalid! :type))
                (map? v)
                (do (when (> (count v) 64) (invalid! :fields))
                    (reduce-kv
                      (fn [out k cell]
                        (let [k (spelling k)]
                          (when (or (> (count k) 128) (contains? out k))
                            (invalid! :keys))
                          (charge! (count k))
                          (assoc out k (walk cell (inc depth))))) {} v))
                (sequential? v)
                (loop [xs (seq v) out []]
                  (if-not xs out
                    (do (when (>= (count out) 10000) (invalid! :rows))
                        (recur (next xs) (conj out (walk (first xs) (inc depth)))))))
                :else (invalid! :type)))]
      (walk value 0))))

(defn- owner! [owner]
  (when-not (and (map? owner) (some? (:actor owner))
                 (map? (:chat-source owner)))
    (invalid! :owner)))

(defn create-store
  "Create with host identity {:actor ..., :chat-source ...}. The optional
   redaction config contains host-held :secrets; it is never taken from rows."
  ([owner] (create-store owner {}))
  ([owner redaction-config]
   (owner! owner)
   (let [providers (try (:providers (ai/config)) (catch Exception _ []))]
     {:owner owner
      :redaction-config (update redaction-config :secrets concat (keep :key providers))
      :state (atom {:closed? false :bytes 0 :datasets {}})})))

(defn- authorize! [store owner]
  (owner! owner)
  (when-not (and (= owner (:owner store)) (:state store)
                 (not (:closed? @(:state store))))
    (invalid! :scope)))

(defn- temporal? [v]
  (and (string? v)
       (or (try (Instant/parse v) true (catch Exception _ false))
           (try (OffsetDateTime/parse v) true (catch Exception _ false))
           (try (LocalDate/parse v) true (catch Exception _ false)))))

(defn- finite-decimal? [v]
  (and (string? v) (<= (count v) 128)
       (re-matches #"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?" v)
       (Double/isFinite (double (bigdec v)))))

(defn- fields! [fields rows source]
  (when-not (and (vector? fields) (<= 1 (count fields) 64))
    (invalid! :fields))
  (when-not (= (count fields) (count (set (map :name fields))))
    (invalid! :fields))
  (mapv
    (fn [{:keys [name path type unit unit-field semantic] :as field}]
      (when-not
        (and (map? field) (string? name) (<= 1 (count name) 64)
             (vector? path) (<= 1 (count path) 12)
             (every? #(and (string? %) (<= 1 (count %) 128)) path)
             (contains? #{:nominal :quantitative :temporal} type)
             (or (nil? unit) (and (string? unit) (<= 1 (count unit) 128)))
             (or (nil? unit-field)
                 (and (string? unit-field) (some #(= unit-field (:name %)) fields)))
             (or (nil? semantic)
                 (contains? #{:transaction-amount :running-balance :current-balance} semantic)))
        (invalid! :fields))
      (doseq [row rows :let [v (get-in row path)] :when (some? v)]
        (when-not
          (case type
            :quantitative
            (or (number? v)
                ;; Exact finance decimals are only admitted under the known
                ;; host source/semantic contract, never by generic coercion.
                (and (= "wise" source) semantic (finite-decimal? v)))
            :temporal (temporal? v)
            :nominal (or (string? v) (number? v) (boolean? v)))
          (invalid! :field-type)))
      (select-keys field [:name :path :type :unit :unit-field :semantic])) fields))

(defn- coverage! [coverage n]
  (when-not (and (map? coverage)
                 (contains? #{:complete :partial :unknown} (:status coverage)))
    (invalid! :coverage))
  (doseq [k [:requested :succeeded :failed]
          :let [v (get coverage k)] :when (some? v)]
    (when-not (and (integer? v) (<= 0 v 1000000000)) (invalid! :coverage)))
  (doseq [k [:succeeded :failed]
          :when (and (:requested coverage) (get coverage k))]
    (when (> (get coverage k) (:requested coverage)) (invalid! :coverage)))
  (when (and (:requested coverage) (:succeeded coverage) (:failed coverage)
             (not= (:requested coverage) (+ (:succeeded coverage) (:failed coverage))))
    (invalid! :coverage))
  (when (and (contains? coverage :has-more)
             (not (boolean? (:has-more coverage))))
    (invalid! :coverage))
  (when (and (= :complete (:status coverage))
             (:requested coverage) (:succeeded coverage)
             (not= (:requested coverage) (:succeeded coverage)))
    (invalid! :coverage))
  (when (and (= :complete (:status coverage))
             (or (true? (:has-more coverage)) (pos? (or (:failed coverage) 0))))
    (invalid! :coverage))
  ;; Snapshot optional host facts too; no arbitrary objects or unbounded labels.
  (let [facts (select-keys coverage [:from :to :has-more :aggregation :limitations
                                    :failed-inputs])
        snapped (snapshot facts)]
    (assoc (merge (select-keys coverage [:status :requested :succeeded :failed])
                  (into {} (map (fn [k] [k (get snapped (name k))]) (keys facts))))
           :row-count n)))

(defn- protect [config v]
  (cond
    (string? v) (mcp/metadata-redact v config)
    (map? v) (into {} (map (fn [[k cell]]
                           [(let [safe (mcp/metadata-redact (spelling k) config)]
                              (if (= safe (spelling k)) k safe))
                            (if (input/sensitive-key? k)
                              "[redacted]" (protect config cell))]) v))
    (sequential? v) (mapv #(protect config %) v)
    (or (nil? v) (boolean? v)) v
    :else (let [s (json/generate-string v)]
            (if (= s (mcp/metadata-redact s config)) v "[redacted]"))))

(defn- sensitive-copies [store dataset]
  (let [state @(:state store)]
    (when (:closed? state) (invalid! :scope))
    ;; Derive knowledge from the bounded, append-only live observations. Failed
    ;; admission contributes nothing, and closing clears the only learned facts.
    (input/sensitive-copies (conj (vec (vals (:datasets state))) dataset))))

(defn- redaction-config [store dataset]
  (update (:redaction-config store) :secrets concat (sensitive-copies store dataset)))

(defn redact
  "Project untrusted values using learned request facts. No execution authority.
   Configured credentials are handled by each boundary's existing projection."
  [store value]
  (let [copies (seq (sensitive-copies store nil))]
    (if copies (protect {:secrets copies} value) value)))

(defn credential-input?
  "Check selected values or compiled source without changing private rows."
  [store dataset value]
  (not= value (protect (redaction-config store dataset) value)))

(defn- descriptor [store id envelope]
  (let [config (redaction-config store envelope)
        base (merge {:dataset-id id :untrusted true}
                    (protect config (select-keys envelope
                                      [:fields :source :captured-at :coverage
                                       :series-field])))
        preview (protect config (vec (take 5 (:rows envelope))))]
    (when (> (json-bytes base) (- descriptor-bytes 128)) (invalid! :descriptor-size))
    (loop [rows preview]
      (let [result (assoc base :preview rows
                         :preview-omitted? (< (count rows)
                                             (min 5 (count (:rows envelope)))))]
        (if (<= (json-bytes result) descriptor-bytes) result
          (recur (pop rows)))))))

(defn- validate-envelope! [envelope]
  (let [{:keys [rows fields source captured-at series-field]} envelope]
    (when-not (and (vector? rows) (<= (count rows) 10000) (every? map? rows)
                   (string? source) (<= 1 (count source) 256)
                   (string? captured-at) (<= (count captured-at) 64)
                   (try (Instant/parse captured-at) true (catch Exception _ false)))
      (invalid! :envelope))
    (let [rows (snapshot rows)
          fields (fields! fields rows source)
          _ (when (and series-field (not (some #(= series-field (:name %)) fields)))
              (invalid! :fields))
          retained (cond-> {:rows rows :fields fields :source source
                            :captured-at captured-at
                            :coverage (coverage! (:coverage envelope) (count rows))}
                     series-field (assoc :series-field series-field))]
      retained)))

(defn retain!
  "Validate and snapshot before atomic admission. No partial dataset survives."
  [store owner envelope]
  (authorize! store owner)
  (let [retained (validate-envelope! envelope)
        size (json-bytes retained)
        id (str (UUID/randomUUID))]
    (when (> size max-bytes) (invalid! :size))
    (locking (:state store)
      (authorize! store owner)
      (let [state @(:state store)]
        (when (or (>= (count (:datasets state)) 8)
                  (> (+ (:bytes state) size) max-bytes))
          (invalid! :size))
        ;; Project against all admitted facts plus this candidate before committing.
        ;; Descriptor failure cannot admit data or poison request knowledge.
        (let [result (descriptor store id retained)]
          (reset! (:state store)
                  (-> state (update :bytes + size)
                      (assoc-in [:datasets id] retained)))
          result)))))

(defn export-store!
  "Host checkpoint data only; never exports secrets or reviewed metadata.
   Refuse unsafe continuation rather than redact or truncate retained values."
  [store owner]
  (locking (:state store)
    (authorize! store owner)
    (let [datasets (:datasets @(:state store))]
      (when-not (= datasets (protect (:redaction-config store) datasets))
        (invalid! :credentials))
      {:version 1 :owner owner :datasets datasets})))

(defn import-store!
  "Host codec entry: validate exact owner, IDs, envelopes and aggregate bounds.
   This creates private observations, never authenticated result metadata."
  [owner saved redaction-config]
  (when-not (and (= #{:version :owner :datasets} (set (keys saved)))
                 (= 1 (:version saved)) (= owner (:owner saved))
                 (map? (:datasets saved)) (<= (count (:datasets saved)) 8))
    (invalid! :checkpoint))
  (let [store (create-store owner redaction-config)
        retained (into {}
                   (map (fn [[id envelope]]
                          (when-not (and (string? id)
                                         (= id (str (try (UUID/fromString id)
                                                        (catch Exception _ nil)))))
                            (invalid! :id))
                          (let [validated (validate-envelope! envelope)]
                            (when-not (= validated envelope) (invalid! :checkpoint))
                            [id validated]))) (:datasets saved))
        size (reduce + 0 (map json-bytes (vals retained)))]
    (when (or (> size max-bytes)
              (not= retained (protect (:redaction-config store) retained)))
      (invalid! :checkpoint))
    (reset! (:state store) {:closed? false :bytes size :datasets retained})
    store))

(defn lookup!
  "Resolve only in this live request and under its exact host owner. Raw rows
   are private data, not authenticated public output or model instructions."
  [store owner id]
  (authorize! store owner)
  (or (get-in @(:state store) [:datasets id]) (invalid! :scope)))

(defn describe [store owner id]
  (locking (:state store)
    (descriptor store id (lookup! store owner id))))

(defn close!
  "Clear all retained values and permanently refuse late callbacks."
  [store owner]
  (owner! owner)
  (when-not (= owner (:owner store)) (invalid! :scope))
  (locking (:state store)
    (reset! (:state store) {:closed? true :bytes 0 :datasets {}}))
  nil)
