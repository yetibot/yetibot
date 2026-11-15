(ns yetibot.models.ai-data-tools
  "Host adapters for request-owned command datasets and terminal data charts."
  (:require [clojure.string :as string]
            [cheshire.core :as json]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.wise :as wise]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-command-tools :as commands]
            [yetibot.models.ai-datasets :as datasets]
            [yetibot.models.ai-input :as input]
            [yetibot.commands.weather :as weather]
            [yetibot.models.ai-charts :as charts]
            [yetibot.models.ai-kroki-tools :as kroki]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tool-trace :as trace]
            [yetibot.util.ai-execution :as execution]))

(def definitions
  {"command"
   {:description (str "Execute one literal Yetibot command, alias or pipeline as the caller. "
                      "Authorized writes run automatically. Use a named alias directly, not history "
                      "or individual replacement lookups. Returns bounded dataset handles when structured "
                      "data exists; rendered text is not numeric chart data. Never retry unknown effects.")
    :inputSchema {:type "object" :additionalProperties false :required ["command"]
                  :properties {:command {:type "string" :minLength 1 :maxLength 4096}}}}
   "chart"
   {:description (str "Chart every retained row using a dataset_id and its declared fields. "
                      "Call alone. Success delivers the actual chart and ends this request, no summary turn. "
                      "Values including financial data use the configured public Kroki and temporary URL "
                      "automatically. Never submit credentials. Use bar for categories, line for time series.")
    :exclusive? true
    :inputSchema {:type "object" :additionalProperties false
                  :required ["dataset_id" "kind" "x" "y"]
                  :properties {:dataset_id {:type "string" :minLength 1 :maxLength 64}
                               :kind {:type "string" :enum ["bar" "line" "point" "area"]}
                               :x {:type "string" :minLength 1 :maxLength 64}
                               :y {:type "string" :minLength 1 :maxLength 64}
                               :series {:type "string" :minLength 1 :maxLength 64}
                               :title {:type "string" :minLength 1 :maxLength 160}}}}})

(defn arguments! [config operation args]
  (if (= operation "command") (commands/arguments! config operation args)
    (let [required #{"dataset_id" "kind" "x" "y"}
          allowed (into required ["series" "title"])]
      (when-not (and (map? args) (every? #(contains? args %) required)
                     (every? allowed (keys args))
                     (every? (fn [[k v]] (and (string? v) (<= 1 (count v) (if (= k "title") 160 64)))) args)
                     (#{"bar" "line" "point" "area"} (get args "kind")))
        (throw (ex-info "Invalid chart arguments." {:ai/error true})))
      ;; Pure preflight over the current owner's retained snapshot. Missing or
      ;; expired datasets keep their existing correctable chart failure; they
      ;; must never be misreported as credential detections.
      (when-let [dataset (try (datasets/lookup! (:dataset-store config)
                                                (:dataset-owner config) (get args "dataset_id"))
                             (catch clojure.lang.ExceptionInfo e
                               (when-not (:dataset/code (ex-data e)) (throw e))
                               nil))]
        (let [selected (filter #(some #{(:name %)} (keep args ["x" "y" "series"])) (:fields dataset))
              values (mapv (fn [row] (mapv #(get-in row (:path %)) selected)) (:rows dataset))
              compiled (charts/compile-chart dataset (dissoc args "dataset_id"))]
          (let [value (json/generate-string
                        [values (when (:ok? compiled) (:request compiled))])]
            (when (not= value (mcp/metadata-redact value (:redaction-config (:dataset-store config))))
            (throw (ex-info "Chart request contains configured credentials."
                            {:ai/error true :ai/error-code :credential-input
                             :ai/problem (trace/sanitize-problem
                                           {:stage :arguments :field "*" :code :credential-input
                                            :execution-began false})}))))))
      args)))

(defn timed [config stage f]
  (let [started (System/nanoTime)]
    (try (f)
         (finally
           (when-let [timings (:stage-timings config)]
             (swap! timings update stage (fnil + 0)
                    (min 300000 (long (/ (- (System/nanoTime) started) 1000000)))))))))

(defn- problem [stage field code began?]
  (trace/sanitize-problem {:stage stage :field field :code code :execution-began began?}))

(defn retain! [config envelope]
  (datasets/retain! (:dataset-store config) (:dataset-owner config) envelope))

(defn observe
  "Keep the original authorized result even when independent retention fails."
  [config f]
  (let [retained (atom []) problems (atom [])
        result (binding [wise/*dataset-observer*
                         (fn [envelope]
                           (try (swap! retained conj (retain! config envelope))
                                (catch Exception _
                                  (swap! problems conj (problem :collection "dataset" :size true)))))]
                 (f))]
    {:result result :datasets @retained :problems @problems}))

(defn- structured-rows [pipeline]
  (let [value (or (:data-collection pipeline) (:data pipeline))]
    (cond (map? value) [value]
          (and (vector? value) (every? #(or (map? %) (nil? %)) value)) value
          :else nil)))

(defn- leaf-paths [row]
  (mapcat (fn [[k v]]
            (let [k (if (keyword? k) (subs (str k) 1) k)]
              (if (map? v) (map #(into [k] %) (leaf-paths v)) [[k]]))) row))

(defn- string-keys [v]
  (cond (map? v) (reduce-kv (fn [out k x]
                              (let [k (if (keyword? k) (subs (str k) 1) k)]
                                (when (contains? out k)
                                  (throw (ex-info "Ambiguous dataset keys." {:ai/error true})))
                                (assoc out k (string-keys x)))) {} v)
        (vector? v) (mapv string-keys v)
        :else v))

(defn- field-type [values]
  (cond
    (and (seq values) (every? number? values)) :quantitative
    (and (seq values)
         (every? (fn [v]
                   (and (string? v)
                        (or (try (java.time.Instant/parse v) true (catch Exception _ false))
                            (try (java.time.OffsetDateTime/parse v) true (catch Exception _ false))
                            (try (java.time.LocalDate/parse v) true (catch Exception _ false))))) values))
    :temporal
    :else :nominal))

(defn- failed-input-count [expressions]
  (let [by-ordinal (into {} (map (juxt :ordinal identity)) expressions)
        failed (filter #(not= :success (:status %)) expressions)
        ancestors (reduce
                    (fn [out expression]
                      (loop [parent (:parent-ordinal expression) seen #{} out out]
                        (if (or (nil? parent) (contains? seen parent)) out
                          (recur (:parent-ordinal (get by-ordinal parent))
                                 (conj seen parent) (conj out parent)))))
                    #{} failed)]
    (count (remove #(contains? ancestors (:ordinal %)) failed))))

(defn- weather-unit [reviewed rows path]
  (when-let [[measurement unit] (get {["temp"] [:temperature "C"]
                                    ["app_temp"] [:temperature "C"]
                                    ["wind_spd"] [:wind "km/h"]
                                    ["rh"] [:humidity "%"]} path)]
    (when (and (every? #(= unit (get-in % [:units measurement])) reviewed)
               (= (mapv #(get-in % [:current (keyword (first path))]) reviewed)
                  (mapv #(get-in % path) rows)))
      unit)))

(defn envelope
  "Only authenticated weather companions supply units. Raw rows remain private;
   generic data never acquires reviewed or complete-source authority."
  [pipeline expressions]
  (when-let [incoming (seq (structured-rows pipeline))]
    (let [rows (mapv string-keys (remove nil? incoming))
          reviewed (:result/public-data (public/projection (:public-result pipeline)))
          reviewed (if (map? reviewed) [reviewed] (vec (remove nil? reviewed)))
          weather? (and (= (count reviewed) (count rows))
                        (every? #(and (= "wttr.in" (:source %))
                                      (= "C" (get-in % [:units :temperature]))
                                      (map? (:current %))) reviewed)
                        (= (mapv #(get-in % [:current :temp]) reviewed) (mapv #(get % "temp") rows)))
          missing (- (count incoming) (count rows))
          failures (max missing (failed-input-count expressions))
          rows (if weather? rows (mapv #(if (nil? %) {} (string-keys %)) incoming))
          paths (distinct (mapcat leaf-paths rows))
          fields (mapv (fn [path]
                         (let [values (keep #(get-in % path) rows)
                               unit (when weather? (weather-unit reviewed rows path))]
                           (cond-> {:name (string/join "." path) :path path
                                    :type (field-type values)}
                             unit (assoc :unit unit))))
                       (filter #(every? (fn [v] (or (nil? v) (string? v) (number? v) (boolean? v)))
                                        (map (fn [r] (get-in r %)) rows)) paths))]
      {:rows rows :fields fields :source (if weather? "weather" "yetibot-command")
       :captured-at (str (java.time.Instant/now))
       :coverage (if weather?
                   {:status (if (pos? failures) :partial :complete)
                    :requested (+ (count rows) failures) :succeeded (count rows) :failed failures}
                   {:status (if (pos? failures) :partial :unknown)
                    :limitations ["Upstream completeness is unknown."]})})))

(defn pipeline-dataset [config pipeline]
  (when-let [e (envelope pipeline [])] (retain! config e)))

(defn- admit! [config source user yetibot-user _tree]
  (when-not (and execution/*request*
                 (not (:closed? @(:budget execution/*request*)))
                 (= execution/*event* (:event execution/*request*))
                 (= source (:chat-source execution/*event*))
                 (= user (:user execution/*event*))
                 (= yetibot-user (:yetibot-user execution/*event*))
                 (= (:actor (:dataset-owner config)) (policy/actor execution/*event*)))
    (throw (ex-info "Command identity or request expired." {:ai/error true})))
  (policy/authorize! (:toolset config) (policy/actor execution/*event*)))

(defn- retain-artifacts! [config event]
  (when (and (:public-data? event) (:kroki-artifacts config)
             (not (:closed? @(:budget execution/*request*))))
    (let [data (:public-data event)
          candidates (cond (sequential? data) data
                           (sequential? (:artifacts data)) (:artifacts data)
                           :else [data])]
      (doseq [artifact candidates
              :when (and (map? artifact) (true? (:ephemeral artifact))
                         (every? string? (map artifact [:type :format :mime-type :id :url]))
                         (integer? (:bytes artifact)) (pos? (:bytes artifact))
                         (every? #(= % (mcp/metadata-redact % config))
                                 (map artifact [:type :format :mime-type :id :url])))]
        (swap! (:kroki-artifacts config) conj
               (select-keys artifact [:type :format :mime-type :bytes :id :url :ephemeral]))))))

(defn- command! [config args source]
  (let [failed-inputs (atom [])
        {:keys [result datasets problems]}
        (observe config
          #(timed config :collection
             (fn []
               (binding [execution/*command-capture* true
                         public/*capture* (partial retain-artifacts! config)
                         weather/*lookup-observer*
                         (fn [{:keys [input failed?]}]
                           (when (and failed? (string? input) (<= 1 (count input) 128))
                             (let [label (-> (mcp/metadata-redact input config)
                                             (string/replace #"[\p{Cntrl}]" " ") string/trim)]
                               (when (seq label)
                                 (swap! failed-inputs (fn [labels] (vec (take 5 (conj labels label)))))))))]
                 (commands/execute! (get args "command")
                   {:chat-source source :user (:user execution/*event*)
                    :yetibot-user (:yetibot-user execution/*event*)
                    :actor (policy/actor execution/*event*)
                    :toolset (or (:toolset config) :legacy-default)
                    :before-expression! (partial admit! config)})))))
        retention-problem (atom nil)
        retained (try
                   (when (:capture-available? result)
                     (when-let [e (envelope (:pipeline result) (:expressions result))]
                       ;; The observer's complete statement is reusable separately; a transformed
                       ;; pipeline is a distinct scope, never replaced with an enclosing statement.
                       (retain! config (cond-> e
                                         (and (= "weather" (:source e)) (seq @failed-inputs))
                                         (assoc-in [:coverage :failed-inputs] @failed-inputs)))))
                   (catch Exception e
                     (reset! retention-problem
                             (problem :collection "dataset"
                                      (if (#{:size :rows :fields :depth :descriptor-size} (:dataset/code (ex-data e)))
                                        :size :type) true))
                     nil))
        descriptors (cond-> datasets retained (conj retained))]
    {:isError (not= :success (:status result))
     :unknownOutcome (= :unknown (:status result))
     :structuredContent
     (cond-> {:untrusted true :status (:status result) :datasets descriptors
              :problems (vec (take 3 (keep trace/sanitize-problem
                                        (concat problems (:problems result) [@retention-problem]))))}
       (empty? descriptors) (assoc :output (:value (:pipeline result))
                                  :data-limitation "No reusable structured dataset was retained."))}))

(defn- credential-field? [dataset args]
  (some (fn [field]
          (and (some #{(:name field)} (keep args ["x" "y" "series"]))
               (some input/sensitive-key? (:path field))))
        (:fields dataset)))

(defn- chart! [config args source]
  (let [compiled
        (timed config :preparation
          #(try
             (let [dataset (datasets/lookup! (:dataset-store config) (:dataset-owner config) (get args "dataset_id"))
                   selected (filter (fn [field] (some #{(:name field)} (keep args ["x" "y" "series"])))
                                    (:fields dataset))
                   values (mapv (fn [row] (mapv (fn [field] (get-in row (:path field))) selected)) (:rows dataset))
                   compiled (charts/compile-chart dataset (dissoc args "dataset_id"))]
               (if (or (credential-field? dataset args)
                         (datasets/credential-input? (:dataset-store config) dataset values)
                         (and (:ok? compiled)
                              (datasets/credential-input? (:dataset-store config) dataset (:request compiled))))
                   {:ok? false :problem (problem :chart "*" :credential-input false)}
                 compiled))
             (catch Exception _ {:ok? false :problem (problem :chart "dataset_id" :unavailable-field false)})))]
    (if-not (:ok? compiled)
      {:isError true :structuredContent {:problem (trace/sanitize-problem (:problem compiled))}}
      (let [rendered (timed config :render
                       #(kroki/call! config "kroki_render"
                          (into {} (map (fn [[k v]] [(name k) v]) (:request compiled))) source))]
        (if (:isError rendered)
          (do (reset! (:chart-stop config)
                      (if (get-in rendered [:structuredContent :problem])
                        "Chart request was rejected before rendering; collected data was not replayed."
                        "Chart rendering failed; collected data was not replayed."))
              rendered)
          (do
            ;; This atom is host-held, not a model/remote result property.
            (reset! (:chart-terminal config)
                    {:caption (:caption compiled) :artifact (dissoc (:structuredContent rendered) :untrusted)})
            rendered))))))

(defn call! [config operation args source]
  (case operation
    "command" (command! config args source)
    "chart" (chart! config args source)))
