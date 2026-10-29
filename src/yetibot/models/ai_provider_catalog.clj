(ns yetibot.models.ai-provider-catalog
  "Bounded model-ID observations. Reads never discover or resolve credentials."
  (:require [yetibot.api.ai :as ai])
  (:import [java.security MessageDigest]
           [java.util.concurrent ArrayBlockingQueue Executors
            ScheduledExecutorService ThreadFactory ThreadPoolExecutor TimeUnit]))

(def freshness-ms 300000)
(def max-entries 32)
(def max-ids 8192)
(def max-id-bytes 512)
(def max-entry-bytes 262144)

(defn new-cache [clock submit current-config]
  {:state (atom {}) :clock clock :submit submit :current-config current-config})

(defn- fingerprint [provider]
  ;; Hash config, not file contents: credential files are opened only by the
  ;; selected discovery request. No connection or credential enters state/jobs.
  (let [value (mapv #(get provider %)
                    [:name :url :key :key-file :gateway-id :model :api
                     :tool-calling :tool-choice :native-tools :max-turns])]
    (apply str (map #(format "%02x" (bit-and 255 %))
                    (.digest (MessageDigest/getInstance "SHA-256")
                             (.getBytes (pr-str value) "UTF-8"))))))

(defn- model-ids [models]
  (when-not (and (vector? models) (:complete? (meta models))
                 (<= (count models) max-ids))
    (throw (ex-info "Incomplete catalog." {})))
  (let [sizes (mapv #(if (ai/nonblank-string? %)
                      (alength (.getBytes ^String % "UTF-8"))
                      (inc max-id-bytes)) models)]
    (when (or (some #(> % max-id-bytes) sizes)
              (> (reduce + 0 sizes) max-entry-bytes))
      (throw (ex-info "Catalog limit." {}))))
  ;; Strip metadata and keep only exact IDs, never provider-returned handles.
  (set models))

(defn- finish! [cache key token ids]
  (swap! (:state cache)
         (fn [state]
           (if (= token (get-in state [key :token]))
             (let [old (get state key)
                   failures (if ids 0 (inc (:failures old 0)))
                   now ((:clock cache))]
               (assoc state key
                      (cond-> {:refreshing false :failures failures
                               :retry-at (+ now (min 300000
                                                (* 5000 (bit-shift-left
                                                          1 (min failures 6)))))
                               :touched now}
                        ids (assoc :ids ids :good-at now))))
             state))))

(defn- refresh! [cache key]
  (let [now ((:clock cache)) token (str (java.util.UUID/randomUUID))
        claimed (atom false)]
    (swap! (:state cache)
           (fn [state]
             (reset! claimed false)
             (let [old (get state key)]
               (if (or (:refreshing old) (< now (:retry-at old 0))
                       (and (:good-at old)
                            (< (- now (:good-at old)) freshness-ms)))
                 state
                 (do (reset! claimed true)
                     (assoc state key (assoc old :token token :refreshing true
                                                  :touched now)))))))
    (when @claimed
      (try
        ;; Queued work captures hashes/tokens only. Resolve current config at
        ;; execution, so removed/changed providers cannot be discovered later.
        ((:submit cache)
         (fn []
           (let [ids (try
                       (when (= token (get-in @(:state cache) [key :token]))
                         (when-let [provider
                                    (some #(when (= key (fingerprint %)) %)
                                          (:providers ((:current-config cache))))]
                           (model-ids (ai/models provider))))
                       (catch Exception _ nil))]
             (finish! cache key token ids))))
        (catch Exception _ (finish! cache key token nil))))))

(defn warm!
  "Background-only scheduling. Reconcile current config before bounded jobs."
  ([cache] (warm! cache ((:current-config cache))))
  ([cache config]
   (let [providers (:providers config)
         keys (if (<= (count providers) max-entries)
                (set (map fingerprint providers)) #{})]
     (swap! (:state cache) select-keys keys)
     (doseq [key keys] (refresh! cache key)))))

(defn infer-provider
  "Immediate exact-ID routing, requiring fresh complete observations of ALL
   current providers. No IO, queueing, fallback, or credential file reads."
  [cache config model]
  (let [providers (:providers config)
        state @(:state cache)
        now ((:clock cache))
        entries (mapv #(get state (fingerprint %)) providers)]
    (when-not (and (<= 2 (count providers) max-entries)
                   (every? #(and (:good-at %)
                                 (< (- now (:good-at %)) freshness-ms)
                                 (contains? % :ids)) entries))
      (ai/fail (str "Model catalogs are not ready, complete, or available. "
                    "Use --provider <name> with --model <exact-id>.")))
    (let [matches (keep (fn [[provider entry]]
                          (when (contains? (:ids entry) model) (:name provider)))
                        (map vector providers entries))]
      (case (count matches)
        1 (first matches)
        0 (ai/fail "No provider advertises that exact model ID. Use --provider <name>.")
        (ai/fail "Model ID is ambiguous across providers. Use --provider <name>.")))))

(defonce runtime (atom nil))

(defn- daemon-factory [name]
  (reify ThreadFactory
    (newThread [_ runnable]
      ;; Never inherit request actors, dynamic bindings, or execution authority.
      (doto (Thread. nil runnable name 0 false) (.setDaemon true)))))

(defn- submit! [job]
  (if-let [workers (:workers @runtime)]
    (.execute ^ThreadPoolExecutor workers ^Runnable job)
    (throw (ex-info "Catalog stopped." {}))))

(defonce shared-cache
  (new-cache #(quot (System/nanoTime) 1000000) submit! ai/config))

(defn- tick! []
  ;; Resolve config outside the lifecycle lock: even a slow config resolver
  ;; cannot block start/stop. Serialize only scheduling with this generation.
  (when-let [generation @runtime]
    (let [config (try ((:current-config shared-cache))
                      (catch Exception _ nil))]
      (locking runtime
        (when (identical? generation @runtime)
          (if config (warm! shared-cache config)
            (reset! (:state shared-cache) {})))))))

(declare stop!)

(defn start!
  "Lifecycle only: schedule metadata work without waiting for config or IO."
  []
  (locking runtime
    (when-not @runtime
      (let [workers (ThreadPoolExecutor.
                      2 2 0 TimeUnit/MILLISECONDS (ArrayBlockingQueue. 32)
                      (daemon-factory "yetibot-ai-model-discovery"))
            timer (Executors/newSingleThreadScheduledExecutor
                    (daemon-factory "yetibot-ai-model-catalog"))
            hook (Thread. nil ^Runnable (fn [] (stop!))
                          "yetibot-ai-model-shutdown" 0 false)]
        (reset! runtime {:workers workers :timer timer :hook hook})
        (.addShutdownHook (Runtime/getRuntime) hook)
        (.scheduleWithFixedDelay ^ScheduledExecutorService timer
                                 ^Runnable tick! 0 30 TimeUnit/SECONDS))))
  nil)

(defn stop! []
  (locking runtime
    (when-let [{:keys [workers timer hook]} @runtime]
      (reset! runtime nil)
      (reset! (:state shared-cache) {})
      (.shutdownNow ^ScheduledExecutorService timer)
      (.shutdownNow ^ThreadPoolExecutor workers)
      (try (.removeShutdownHook (Runtime/getRuntime) hook)
           (catch IllegalStateException _ nil))))
  nil)
