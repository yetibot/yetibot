(ns yetibot.models.ai-usage
  "Bot-wide aggregate reports, with no provider-account billing access."
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [yetibot.api.ai :as ai]
            [yetibot.core.db.ai-usage :as store]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.util.ai-execution :as execution])
  (:import [java.math RoundingMode]
           [java.time Instant LocalDate OffsetDateTime YearMonth ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def help-text
  (str "Usage: ai usage [YYYY-MM | <start> <end>]. "
       "Use YYYY-MM-DD or ISO instants with Z/offset. "
       "Start is inclusive; end is exclusive. Default: current UTC month."))

(defn- month-start [^YearMonth month]
  (.toInstant (.atStartOfDay (.atDay month 1) ZoneOffset/UTC)))

(defn- point [text]
  (if (re-matches #"[0-9]{4}-[0-9]{2}-[0-9]{2}" text)
    (.toInstant (.atStartOfDay (LocalDate/parse text) ZoneOffset/UTC))
    (.toInstant (OffsetDateTime/parse text))))

(defn window
  ([text] (window text (Instant/now)))
  ([text ^Instant now]
   (try
     (when-not (and (or (nil? text) (string? text))
                    (<= (count (or text "")) 200))
       (ai/fail help-text))
     (let [parts (when-not (string/blank? text)
                   (string/split (string/trim text) #"\s+"))
           [start end]
           (case (count parts)
             0 (let [month (YearMonth/from (.atOffset now ZoneOffset/UTC))]
                 [(month-start month) (month-start (.plusMonths month 1))])
             1 (let [text (first parts)]
                 (when-not (re-matches #"[0-9]{4}-[0-9]{2}" text)
                   (ai/fail help-text))
                 (let [month (YearMonth/parse text)]
                   [(month-start month) (month-start (.plusMonths month 1))]))
             2 (mapv point parts)
             (ai/fail help-text))]
       (when-not (.isBefore ^Instant start end) (ai/fail help-text))
       {:start start :end end})
     (catch Exception _ (ai/fail help-text)))))

(defn- count-label [value]
  (if (nil? value) "n/a"
    (String/format Locale/US "%,d" (object-array [(biginteger value)]))))

(defn- counted [value singular plural]
  (str (count-label value) " " (if (= 1 value) singular plural)))

(defn- usd [value]
  (let [value (bigdec value)
        rounded (cond (>= value 1) (.setScale value 2 RoundingMode/HALF_UP)
                      (>= value 0.0001M)
                      (.setScale value 4 RoundingMode/HALF_UP)
                      :else value)]
    (if (zero? value) "$0.00"
      (str "$" (.toPlainString (.stripTrailingZeros rounded))))))

(defn- spend [{:keys [reported_usd estimated_usd cost_unknown_events events]}]
  (let [positive (keep (fn [[value label]]
                         (when (and (number? value) (pos? value))
                           (str (usd value) " " label)))
                       [[reported_usd "reported"] [estimated_usd "estimated"]])
        known? (and (number? reported_usd) (number? estimated_usd)
                    (< cost_unknown_events events))]
    (string/join " · "
      (concat (if (seq positive) positive
                [(if known? "$0.00 known cost" "Cost unavailable")])
              (when (pos? cost_unknown_events)
                [(str (count-label cost_unknown_events) " unpriced")])))))

(defn- format-date [instant pattern]
  (.format (DateTimeFormatter/ofPattern pattern Locale/US)
           (.atOffset ^Instant instant ZoneOffset/UTC)))

(defn- period-label [{:keys [start end]}]
  (let [start (Instant/parse start) end (Instant/parse end)
        month (YearMonth/from (.atOffset start ZoneOffset/UTC))
        point-label #(if (and (zero? (mod (.getEpochSecond ^Instant %) 86400))
                              (zero? (.getNano ^Instant %)))
                       (format-date % "uuuu-MM-dd") (str %))]
    (if (and (= start (month-start month))
             (= end (month-start (.plusMonths month 1))))
      (str (format-date start "MMM uuuu") " UTC")
      (str (point-label start) " → " (point-label end)
           " UTC (end exclusive)"))))

(defn- coverage-note [{:keys [status started_at]}]
  (case status
    "partial" (str "Tracking since "
                   (format-date (Instant/parse started_at) "MMM d")
                   ". Earlier usage is unknown.")
    "before-tracking"
    "Before tracking; an empty report does not mean zero usage."
    "unknown" "Tracking coverage unknown."
    nil))

(defn readable [{:keys [window coverage groups groups_truncated totals]}]
  (let [models (take 5
                 (sort-by (juxt #(- (+ (or (:reported_usd %) 0M)
                                      (or (:estimated_usd %) 0M)))
                                #(- (:events %)) :provider :model :scope)
                          groups))
        status (keep (fn [[key label]]
                       (when (pos? (or (get totals key) 0))
                         (str (count-label (get totals key)) " " label)))
                     [[:failed_events "failed"]
                      [:uncertain_events "pending/unknown"]])
        units (keep (fn [[key singular plural]]
                      (when (pos? (or (get totals key) 0))
                        (counted (get totals key) singular plural)))
                    [[:images "image" "images"]
                     [:seconds "video second" "video seconds"]])]
    (string/join "\n"
      (concat
        [(str "Yetibot AI usage · " (period-label window))]
        (if (zero? (:events totals)) ["No recorded usage."]
          [(spend totals)
           (string/join " · "
             (concat
               (when (pos? (:http_attempts totals))
                 [(counted (:http_attempts totals) "request" "requests")])
               (when (pos? (:cli_sessions totals))
                 [(counted (:cli_sessions totals) "CLI session" "CLI sessions")])
               status units))
           (if (some some? [(:input_count totals) (:output_count totals)])
             (str "Tokens: " (count-label (:input_count totals)) " in / "
                  (count-label (:output_count totals)) " out"
                  (when (pos? (:text_counter_unknown_events totals)) " (partial)"))
             "Tokens unavailable")])
        (when (seq models)
          (cons ""
            (map (fn [group]
                   (str (:provider group) "/" (:model group)
                        (when (= "session" (:scope group)) " [CLI]")
                        " · " (counted (:events group) "event" "events")
                        " · " (spend group))) models)))
        [""]
        (when (or groups_truncated (> (count groups) 5))
          [(str "Showing " (count models) " of " (count groups)
                (if groups_truncated "+ model groups; totals include all."
                  " model groups."))])
        (when-let [note (coverage-note coverage)] [note])
        ["More in Explore data."]))))

(def ^:private total-fields
  [:events :http_attempts :cli_sessions :failed_events :uncertain_events
   :http_requests :cli_requests :request_count_unknown_events
   :reported_usd :estimated_usd :cost_unknown_events
   :input_count :output_count :uncached_count :cached_count :thinking_count
   :neurons :images :seconds :text_counter_unknown_events])

(def ^:private public-counter-keys
  {:input_tokens :input_count :output_tokens :output_count
   :uncached_tokens :uncached_count :cached_tokens :cached_count
   :reasoning_tokens :thinking_count
   :token_unknown_events :text_counter_unknown_events})

(defn- public-fields [row fields]
  (select-keys (set/rename-keys row public-counter-keys) fields))

(defn report!
  "Any trusted human chat actor may read bot-wide totals; no native report tool."
  [event text]
  (execution/sensitive!)
  (when-not (policy/actor event)
    (ai/fail "AI usage reports require a trusted chat user."))
  (let [{:keys [start end]} (window text)
        result (try (store/report! start end)
                    (catch Exception _ (ai/fail "AI usage report is unavailable.")))
        since (some-> (:coverage_started_at result) .toInstant)
        coverage (cond (nil? since) "unknown"
                       (not (.isBefore ^Instant since end)) "before-tracking"
                       (.isBefore ^Instant start since) "partial"
                       :else "started-before-window")]
    {:schema_version 1 :scope "yetibot" :currency "USD"
     :window {:start (str start) :end (str end) :timezone "UTC"
              :start_inclusive true :end_exclusive true}
     :coverage {:started_at (some-> since str) :status coverage
                :historical_backfill false :provider_account_usage false}
     :semantics {:usd "per-request figures/partial estimates, not account invoices"
                 :model_labels "requested routes or CLI-reported models"
                 :counter_definitions
                 "provider-native tokens; cached/reasoning may overlap"
                 :cli_attribution "aggregate session start"
                 :opaque_external_tools
                 "unobservable unless included in reported stats"}
     :totals (public-fields (:totals result) total-fields)
     :groups (mapv #(public-fields % (concat [:provider :model :scope] total-fields))
                   (:groups result))
     :groups_truncated (boolean (:groups_truncated result))}))
