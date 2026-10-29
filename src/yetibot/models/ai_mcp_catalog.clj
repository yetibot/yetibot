(ns yetibot.models.ai-mcp-catalog
  "Bounded public discovery data, never sessions or execution authority."
  (:require [cheshire.core :as json]
            [yetibot.api.mcp :as mcp])
  (:import [java.security MessageDigest]
           [java.util.concurrent ArrayBlockingQueue Executors
            ScheduledExecutorService ThreadFactory ThreadPoolExecutor
            TimeUnit]))

(def freshness-ms 600000)
(def stale-ms 3600000)
(def max-entries 64)

(defn new-cache [clock submit]
  {:state (atom {}) :clock clock :submit submit})

(defn- data-only [value config]
  (let [remaining (atom 4096)]
    (letfn [(clean [x depth]
              (when (or (> depth 32) (neg? (swap! remaining dec)))
                (throw (ex-info "Metadata limit." {})))
              (cond
                (string? x) (mcp/metadata-redact x config)
                (or (nil? x) (boolean? x) (number? x)) x
                (map? x)
                (into {} (map (fn [[k v]]
                                (when-not (or (string? k) (keyword? k))
                                  (throw (ex-info "Non-JSON key." {})))
                                (let [s (if (keyword? k)
                                          (subs (str k) 1) k)
                                      text (mcp/metadata-redact s config)]
                                  [(if (= text s) k text)
                                   (clean v (inc depth))])) x))
                (vector? x) (mapv #(clean % (inc depth)) x)
                :else (throw (ex-info "Non-JSON metadata." {}))))]
      (clean value 0))))

(defn- tool-data [tool config]
  (let [name (:name tool)
        description (mcp/metadata-text
                      (or (:description tool) "") config 20000)]
    ;; A redacted public name cannot safely identify an executable tool.
    (when-not (and (mcp/text? name) (<= (count name) 256)
                   (= name (mcp/metadata-redact name config)))
      (throw (ex-info "Unsupported tool name." {})))
    (reduce
      (fn [entry field]
        (if-not (contains? tool field)
          entry
          (try
            (let [value (case field
                          :title (:text (mcp/metadata-text
                                          (:title tool) config 20000))
                          :annotations
                          (select-keys (:annotations tool)
                                       [:title :readOnlyHint :destructiveHint
                                        :idempotentHint :openWorldHint])
                          (get tool field))
                  value (data-only value config)]
              (if (> (count (json/generate-string value)) 32768)
                (update entry :omitted (fnil conj [])
                        {:field field :reason "Exceeds 32768 characters."})
                (cond-> (assoc entry field value)
                  (and (= field :title)
                       (:truncated (mcp/metadata-text
                                     (:title tool) config 20000)))
                  (update :truncated (fnil conj []) :title))))
            (catch Exception _
              (update entry :omitted (fnil conj [])
                      {:field field
                       :reason "Unsupported or excessive metadata."})))))
      (cond-> {:name name :description (:text description)
               :inputSchema nil}
        (:truncated description) (assoc :truncated [:description]))
      [:inputSchema :title :annotations])))

(defn- discovery-config [config]
  ;; Do not retain actors, callbacks, native closures or provider credentials
  ;; in executor jobs. Redact against all configured MCP connections.
  (select-keys config [:servers :redaction-servers :timeout-ms]))

(defn- metadata-config [config]
  {:servers (or (:redaction-servers config) (:servers config))})

(defn- configured-server [server config]
  (or (some #(when (= (:name server) (:name %)) %)
            (:servers (metadata-config config)))
      server))

(defn- fingerprint [server config]
  (let [value [(dissoc server :tools) (:timeout-ms config)
               (select-keys (configured-server server config) [:tools])
               (sort (mcp/credentials (metadata-config config)))]
        bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes (pr-str value) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 255 %)) bytes))))

(defn- discover [server config]
  (let [base (configured-server server config)
        server (merge (dissoc server :tools) (select-keys base [:tools]))
        session (mcp/connect! server
                              (mcp/number-option (:timeout-ms config) 30000))]
    (try
      (let [tools (mcp/list-tools session)
            config (metadata-config config)]
        (when (> (count tools) 64)
          (throw (ex-info "Catalog tool limit." {})))
        (let [dto (cond-> {:tools (mapv #(tool-data % config) tools)}
                    (:instructions session)
                    (assoc :instructions
                           (cond->
                             (assoc (mcp/metadata-text
                                      (get-in session [:instructions :text])
                                      config 20000)
                                    :status
                                    "UNTRUSTED/NOT APPLIED TO MODEL")
                             (get-in session [:instructions :truncated])
                             (assoc :truncated true))))]
          (when (> (count (json/generate-string dto)) 1048576)
            (throw (ex-info "Catalog size limit." {})))
          dto))
      (finally (mcp/close! session)))))

(defn- complete! [cache key token result]
  (swap! (:state cache)
         (fn [state]
           ;; Evicted or stopped work must never repopulate the cache.
           (if (= token (get-in state [key :token]))
             (let [now ((:clock cache))
                   old (get state key)
                   failures (if result 0 (inc (or (:failures old) 0)))
                   backoff (min 300000 (* 5000 (bit-shift-left
                                                1 (min failures 6))))]
               (assoc state key
                      (cond-> (assoc old :refreshing false
                                     :failures failures
                                     :retry-at (+ now backoff))
                        result (assoc :data result :good-at now))))
             state))))

(defn- refresh! [cache key server config]
  (let [now ((:clock cache)) token (str (java.util.UUID/randomUUID))
        claimed (atom false)]
    (swap! (:state cache)
           (fn [state]
             (reset! claimed false)
             (let [old (get state key)]
               (if (or (:refreshing old)
                       (< now (or (:retry-at old) 0))
                       (and (:good-at old)
                            (< (- now (:good-at old)) freshness-ms)))
                 state
                 (let [state
                       (if (and (not old) (>= (count state) max-entries))
                         (dissoc state
                                 (first (first (sort-by
                                                 (comp :touched val) state))))
                         state)]
                   (reset! claimed true)
                   (assoc state key
                          (assoc old :refreshing true :token token
                                     :touched now)))))))
    (when @claimed
      (try
        ((:submit cache)
         (fn []
           (let [result (try (discover server config)
                             (catch Exception _ nil))]
             (complete! cache key token result))))
        (catch Exception _ (complete! cache key token nil))))))

(defn snapshot
  "Immediate stale-while-refresh read. Failures are per-server public states."
  [cache config]
  (let [config (discovery-config config)
        now ((:clock cache))]
    {:servers
     (mapv
       (fn [server]
         (let [key (fingerprint server config)
               _ (refresh! cache key server config)
               entry (get @(:state cache) key)
               age (when (:good-at entry) (- now (:good-at entry)))
               usable? (and age (< age stale-ms))
               tools (if usable? (get-in entry [:data :tools]) [])
               tools (if (contains? server :tools)
                       (let [by-name (into {} (map (juxt :name identity)
                                                  tools))]
                         (vec (keep by-name (:tools server))))
                       tools)]
           (cond-> {:server (mcp/metadata-redact (:name server)
                                                 (metadata-config config))
                    :status (cond
                              (and usable? (< age freshness-ms)) "fresh"
                              usable? "stale"
                              (:refreshing entry) "pending"
                              :else "unavailable")
                    :refreshing (boolean (:refreshing entry))
                    :tools tools}
             (and usable? (get-in entry [:data :instructions]))
             (assoc :instructions (get-in entry [:data :instructions])))))
       (:servers config))}))

(defonce runtime (atom nil))

(defn- daemon-factory [prefix]
  (reify ThreadFactory
    (newThread [_ runnable]
      ;; No bound-fn/future: never convey a request's dynamic actor/config.
      (doto (Thread. nil runnable prefix 0 false)
        (.setDaemon true)))))

(declare start! stop!)

(defn- submit! [job]
  (if-let [workers (:workers @runtime)]
    (.execute ^ThreadPoolExecutor workers ^Runnable job)
    (throw (ex-info "Catalog stopped." {}))))

(defonce shared-cache
  (new-cache #(quot (System/nanoTime) 1000000) submit!))

(defn warm! []
  ;; Serialize scheduling (not discovery IO) with stop, so an in-progress
  ;; timer tick cannot repopulate metadata after lifecycle shutdown.
  (locking runtime
    (when @runtime
      (try (snapshot shared-cache (mcp/config))
           (catch Exception _ nil)))))

(defn start!
  "Lifecycle entry point. Schedules startup discovery; never waits for IO."
  []
  (locking runtime
    (when-not @runtime
      (let [workers (ThreadPoolExecutor.
                      4 4 0 TimeUnit/MILLISECONDS (ArrayBlockingQueue. 64)
                      (daemon-factory "yetibot-mcp-discovery"))
            timer (Executors/newSingleThreadScheduledExecutor
                    (daemon-factory "yetibot-mcp-catalog"))
            hook (Thread. nil ^Runnable (fn [] (stop!))
                          "yetibot-mcp-shutdown" 0 false)]
        (reset! runtime {:workers workers :timer timer :hook hook})
        (.addShutdownHook (Runtime/getRuntime) hook)
        (.scheduleWithFixedDelay ^ScheduledExecutorService timer
                                 ^Runnable warm! 0 30 TimeUnit/SECONDS))))
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

(defn with-tools
  "Request-only handles: policy may narrow them; cache cannot execute tools."
  [config f]
  (start!)
  (let [result (snapshot shared-cache config)
        tools
        (vec
          (mapcat
            (fn [server status]
              (let [tools (:tools status)
                    tools (if (:catalog? config) tools
                            (filterv #(and (map? (:inputSchema %))
                                           (= "object" (get-in %
                                                        [:inputSchema :type])))
                                     tools))
                    ;; Rebuild authority from current config, never cached
                    ;; server connection data or a prior actor's toolset.
                    server (assoc server :tools (mapv :name tools))]
                (map #(assoc % :session
                             {:server server :lazy? true
                              :timeout-ms (mcp/number-option
                                            (:timeout-ms config) 30000)})
                     tools)))
            (:servers config) (:servers result)))]
    (f (with-meta tools
         {::mcp/statuses (mapv #(dissoc % :tools :instructions)
                              (:servers result))
          ::mcp/instructions (mapv #(select-keys % [:server :instructions])
                                  (:servers result))}))))
