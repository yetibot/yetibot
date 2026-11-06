(ns yetibot.models.ai-usage
  "Bot-wide aggregate reports, with no provider-account billing access."
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [yetibot.api.ai :as ai]
            [yetibot.core.db.ai-usage :as store]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.util.ai-execution :as execution])
  (:import [java.time Instant LocalDate OffsetDateTime YearMonth ZoneOffset]))

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
  (if (nil? value) "unavailable" (str value)))

(defn- usd [value]
  (if (nil? value) "unavailable"
    (let [value (bigdec value)]
      (if (zero? value) "$0.00"
        (str "$" (.toPlainString (.stripTrailingZeros value)))))))

(defn- known-units [totals]
  (string/join "; "
    (keep (fn [[key label]]
            (when-some [value (get totals key)] (str label " " value)))
          [[:images "images"] [:seconds "video seconds"] [:neurons "neurons"]])))

(defn readable [{:keys [window coverage groups groups_truncated totals]}]
  (let [units (known-units totals)]
    (string/join "\n"
      (concat
        [(str "Yetibot AI usage — UTC [" (:start window) ", " (:end window) ")")
         (str "Tracking began: " (or (:started_at coverage) "unavailable") ".")
         (case (:status coverage)
           "partial"
           (str "Partial history: this window begins before tracking. "
                "Earlier usage is unknown.")
           "before-tracking"
           (str "This window predates the rollout marker; "
                "missing history does not mean zero usage.")
           "unknown"
           (str "Tracking coverage is unavailable; "
                "no complete total can be inferred.")
           "This window starts at or after tracking began.")
         (str "Recorded events: " (:events totals) " (" (:http_attempts totals)
              " HTTP attempts, " (:cli_sessions totals) " CLI sessions; "
              (:failed_events totals) " failed, " (:uncertain_events totals)
              " pending/uncertain).")
         (str "Known requests: " (:http_requests totals) " HTTP receipts + "
              (:cli_requests totals) " CLI-reported; request counts unavailable for "
              (:request_count_unknown_events totals) " events.")
         (str "USD subtotals: reported " (usd (:reported_usd totals))
              "; estimated " (usd (:estimated_usd totals)) "; unpriced "
              (:cost_unknown_events totals) " events.")
         (str "Native input/output token counters: "
              (count-label (:input_count totals))
              " / " (count-label (:output_count totals))
              "; incomplete token counters for "
              (:text_counter_unknown_events totals)
              " events.")]
        (when (seq units) [(str "Other known units: " units ".")])
        (when (pos? (:cli_sessions totals))
          ["CLI sessions are aggregates booked at session start, not per-call traces."])
        (when groups_truncated
          ["Model breakdown limited to 200 groups; global totals include all groups."])
        (when (seq groups)
          ["Provider/model breakdown (requested routes; CLI-reported models):"])
        (map (fn [group]
               (str (:provider group) " / " (:model group)
                    (when (= "session" (:scope group)) " [CLI aggregate]")
                    " — " (:events group) " events; known in/out "
                    (count-label (:input_count group)) "/"
                    (count-label (:output_count group))
                    "; reported " (usd (:reported_usd group))
                    "; estimated " (usd (:estimated_usd group))
                    "; unpriced " (:cost_unknown_events group))) groups)
        ["Token definitions vary by provider; cached/reasoning counters may overlap."
         "Missing prices/usage are unavailable, never assumed free."
         (str "Subtotals are not account invoices or external-app usage; "
              "estimates may omit fees.")]))))

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
