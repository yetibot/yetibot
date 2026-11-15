(ns yetibot.models.ai-charts
  "Pure inline data charts. Lookup, authorization, credential checks and artifact
   authentication remain the caller's responsibility at the render boundary."
  (:require [cheshire.core :as json]
            [clojure.string :as string])
  (:import [java.time Instant LocalDate OffsetDateTime]))

(def ^:private remedies
  {:required "Supply the required chart field."
   :type "Use fields and values with the required chart types."
   :enum "Choose bar, line, point or area."
   :size "Reduce the requested source scope; data was not sampled."
   :unknown-field "Remove unsupported chart arguments."
   :unavailable-field "Choose a field from the dataset descriptor."
   :incompatible-unit "Use a quantitative field with consistent, explicit units."
   :incomplete-scope "Use complete authoritative observations and distinguish each series."})

(defn- reject! [field code]
  (throw (ex-info "Chart cannot be compiled."
                  {:chart/problem {:stage :chart :field field :code code
                                   :remedy (get remedies code)
                                   :execution-began false}})))

(defn- label [s]
  (let [s (-> (str s) (string/replace #"[\p{Cntrl}@<>`*_]" " ") string/trim)]
    (subs s 0 (min 160 (count s)))))

(defn- finite-number? [v]
  (and (number? v) (not (ratio? v)) (Double/isFinite (double v))))

(defn- temporal? [v]
  (and (string? v)
       (or (try (Instant/parse v) true (catch Exception _ false))
           (try (OffsetDateTime/parse v) true (catch Exception _ false))
           (try (LocalDate/parse v) true (catch Exception _ false)))))

(defn- number-value [dataset field v path]
  (cond
    (nil? v) nil
    (finite-number? v) v
    (and (= "wise" (:source dataset)) (:semantic field)
         (string? v) (<= (count v) 128)
         (re-matches #"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?" v))
    (let [n (bigdec v)] (if (finite-number? n) n (reject! path :type)))
    :else (reject! path :type)))

(defn- field! [fields name path]
  (when-not (string? name) (reject! path :type))
  (let [field (or (some #(when (= name (:name %)) %) fields)
                  (reject! path :unavailable-field))]
    (when (or (and (:unit field) (not (and (string? (:unit field)) (<= 1 (count (:unit field)) 128))))
              (and (:semantic field)
                   (or (not (#{:transaction-amount :running-balance :current-balance} (:semantic field)))
                       (not (or (:unit-field field)
                                (and (string? (:unit field)) (re-matches #"[A-Z]{3}" (:unit field))))))))
      (reject! path :incompatible-unit))
    field))

(defn- cell [row field] (get-in row (:path field)))

(defn- unit-value? [field value]
  (and (string? value)
       (re-matches (if (:semantic field) #"[A-Z]{3}"
                       #"[A-Za-z%°/][A-Za-z0-9%°/ ._-]{0,31}") value)
       (or (nil? (:unit field)) (= (:unit field) value))))

(defn- dimension! [dataset row field path]
  (let [v (if (= :quantitative (:type field))
            (number-value dataset field (cell row field) path)
            (cell row field))]
    (when-not (or (nil? v)
                  (case (:type field)
                    :temporal (temporal? v)
                    :quantitative (finite-number? v)
                    :nominal (or (string? v) (boolean? v) (finite-number? v))
                    false))
      (reject! path :type))
    (when (and (string? v) (> (count v) 262144)) (reject! "dataset" :size))
    v))

(defn- title-for [y intent]
  (case (:semantic y)
    :transaction-amount "Transaction activity"
    :running-balance "Statement running-balance points"
    :current-balance "Current balance snapshot"
    (label (or (get intent "title") (:name y)))))

(defn- axis-title [field]
  (str (label (:name field)) (when (:unit field) (str " (" (label (:unit field)) ")"))))

(defn- caption [dataset title missing]
  (let [{:keys [status requested succeeded from to has-more]} (:coverage dataset)]
    (str title ". "
         (case status :complete "Complete declared scope" :partial "Partial coverage" "Coverage unknown")
         (when (and requested succeeded) (str ": " succeeded " of " requested " inputs"))
         "; " (count (:rows dataset)) " observations."
         (when (pos? missing) (str " " missing " missing measurement(s); gaps retained."))
         (when has-more " Source has more rows; not a complete history.")
         (when-let [failed (seq (get-in dataset [:coverage :failed-inputs]))]
           (str " Missing inputs: " (string/join ", " (map label (take 5 failed)))
                (when (> (count failed) 5) " (additional inputs omitted)") "."))
         (when (and from to) (str " Period: " (label from) " to " (label to) "."))
         (case (:semantic (:selected-y dataset))
           :transaction-amount " Credits/debits include internal moves; not necessarily income/spending. Fees are not deducted again."
           :running-balance " Observed statement points, not daily or opening balances."
           :current-balance " Snapshot only, not historical balances."
           "")
         " Source: " (label (:source dataset)) "; captured " (label (:captured-at dataset)) ".")))

(defn- compile* [{:keys [rows fields coverage series-field] :as dataset} intent]
  (when-not (map? intent) (reject! "*" :type))
  (when-not (every? #{"kind" "x" "y" "series" "title"} (keys intent))
    (reject! "*" :unknown-field))
  (doseq [k ["kind" "x" "y"]]
    (when-not (contains? intent k) (reject! k :required)))
  (when-not (contains? #{"bar" "line" "point" "area"} (get intent "kind"))
    (reject! "kind" :enum))
  (when (contains? intent "title")
    (when-not (string? (get intent "title")) (reject! "title" :type))
    (when-not (<= 1 (count (get intent "title")) 160) (reject! "title" :size)))
  (when-not (and (vector? rows) (seq rows) (every? map? rows))
    (reject! "dataset" :incomplete-scope))
  (when (> (count rows) 10000) (reject! "dataset" :size))
  (when-not (and (vector? fields) (<= 1 (count fields) 64)
                 (= (count fields) (count (set (map :name fields))))
                 (every? #(and (string? (:name %)) (<= 1 (count (:name %)) 64)
                               (vector? (:path %)) (<= 1 (count (:path %)) 12)
                               (every? (fn [p] (and (string? p) (<= 1 (count p) 128))) (:path %))
                               (#{:nominal :quantitative :temporal} (:type %))) fields))
    (reject! "dataset" :type))
  (when-not (contains? #{:complete :partial :unknown} (:status coverage))
    (reject! "dataset" :incomplete-scope))
  (let [kind (get intent "kind")
        x (field! fields (get intent "x") "x")
        y (field! fields (get intent "y") "y")
        series (when (contains? intent "series") (field! fields (get intent "series") "series"))
        host-series (when series-field (field! fields series-field "series"))
        series (or series host-series)
        unit (when (:unit-field y) (field! fields (:unit-field y) "y"))
        x-unit (when (:unit-field x) (field! fields (:unit-field x) "x"))
        x-units (when x-unit (set (map #(cell % x-unit) rows)))
        _ (when (and x-unit
                     (or (not= :nominal (:type x-unit))
                         (not= 1 (count x-units))
                         (not-every? #(unit-value? x %) x-units)))
            (reject! "x" :incompatible-unit))
        x (if x-unit (assoc x :unit (first x-units)) x)
        trend? (contains? #{"line" "area"} kind)
        _ (when-not (= :quantitative (:type y)) (reject! "y" :type))
        _ (when (and trend? (not (#{:temporal :quantitative} (:type x)))) (reject! "x" :type))
        _ (when (and (or series host-series) (some #(and % (not= :nominal (:type %))) [series host-series]))
            (reject! "series" :type))
        _ (when (and unit (not= :nominal (:type unit))) (reject! "y" :incompatible-unit))
        _ (when (and (= :current-balance (:semantic y)) (or trend? (= :temporal (:type x))))
            (reject! "y" :incomplete-scope))
        _ (when (and (= :running-balance (:semantic y))
                     (or (not= :temporal (:type x)) (not= :complete (:status coverage)) (:has-more coverage)))
            (reject! "y" :incomplete-scope))
        values (mapv (fn [row]
                       (let [xv (dimension! dataset row x "x")
                             yv (number-value dataset y (cell row y) "y")
                             sv (when series (dimension! dataset row series "series"))
                             hv (when host-series (dimension! dataset row host-series "series"))
                             uv (when unit (cell row unit))]
                         (when (or (and series (nil? sv)) (and host-series (nil? hv)))
                           (reject! "series" :incomplete-scope))
                         (when (and unit (not (unit-value? y uv)))
                           (reject! "y" :incompatible-unit))
                         (when (and (= :running-balance (:semantic y)) (or (nil? xv) (nil? yv)))
                           (reject! "y" :incomplete-scope))
                         (cond-> {:x xv :y yv}
                           series (assoc :series sv)
                           host-series (assoc :hostSeries hv)
                           unit (assoc :unit uv)))) rows)
        _ (when-not (some #(some? (:y %)) values) (reject! "y" :incomplete-scope))
        ;; No implicit sum, stack or overlapping bars: ask for a distinguishing
        ;; series instead of hiding repeated observations under one mark.
        _ (when (and (not= "point" kind)
                     (not= (count values) (count (set (map #(select-keys % (if (= "bar" kind)
                                                       [:x :series :unit]
                                                       [:x :series :hostSeries :unit])) values)))))
            (reject! "series" :incomplete-scope))
        title (title-for y intent)
        encoding (cond-> {:x {:field "x" :type (name (:type x)) :title (axis-title x)}
                          :y {:field "y" :type "quantitative" :title (axis-title y) :stack nil}}
                   series (assoc :color {:field "series" :type "nominal" :title (label (:name series))})
                   host-series (assoc :detail {:field "hostSeries" :type "nominal"})
                   (and (= "bar" kind) series) (assoc :xOffset {:field "series" :type "nominal"}))
        ;; Null must break paths, not be mapped to zero or filtered into a
        ;; misleading connection across a missing observation.
        chart {:mark {:type kind :invalid (if trend? "break-paths-show-domains" "filter")}
               :encoding encoding}
        mixed? (> (count (set (map :unit values))) 1)
        document (merge {:title title :data {:values values}}
                        (if mixed?
                          {:facet {:field "unit" :type "nominal" :title (label (:name unit))}
                           :spec chart :resolve {:scale {:y "independent"}}}
                          chart))
        document (if (and unit (not mixed?))
                   (assoc-in document [:encoding :y :title]
                             (str (label (:name y)) " (" (:unit (first values)) ")")) document)
        source (json/generate-string document)]
    (when (> (alength (.getBytes ^String source "UTF-8")) 262144) (reject! "dataset" :size))
    {:ok? true :request {:type "vegalite" :source source :format "auto"}
     :caption (caption (assoc dataset :selected-y y) title (count (filter #(or (nil? (:x %)) (nil? (:y %))) values)))
     :coverage coverage}))

(defn compile-chart
  "Compile a host-resolved typed envelope and string-keyed intent. Returns a
   request or a static safe problem; never samples, fetches or renders data."
  [dataset intent]
  (try (compile* dataset intent)
       (catch Exception e
         {:ok? false :problem (or (:chart/problem (ex-data e))
                                 {:stage :chart :field "dataset" :code :type
                                  :remedy (:type remedies) :execution-began false})})))

(defn render-chart!
  "Invoke the existing guarded render adapter exactly once after compilation.
   The returned :result is the callback's actual result, not artifact proof."
  [render! compiled]
  (if-not (:ok? compiled) compiled
    {:result (render! (:request compiled))
     :caption (:caption compiled) :coverage (:coverage compiled)}))
