(ns yetibot.test.models.ai-usage
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.models.ai-usage :as usage]))

(def totals
  {:events 12 :http_attempts 10 :cli_sessions 2
   :failed_events 0 :uncertain_events 0
   :reported_usd 1.23456M :estimated_usd 0.023456M
   :cost_unknown_events 1 :input_count 12345 :output_count 678
   :text_counter_unknown_events 0 :images nil :seconds nil})

(def report
  {:window {:start "2026-11-01T00:00:00Z" :end "2026-12-01T00:00:00Z"}
   :coverage {:status "started-before-window"}
   :totals totals
   :groups [(assoc totals :provider "xai" :model "grok-4.7"
                         :scope "request" :events 10)]})

(fact "the default report is a short spend-first summary"
  (usage/readable report)
  => (str "Yetibot AI usage · Nov 2026 UTC\n"
          "$1.23 reported · $0.0235 estimated · 1 unpriced\n"
          "10 requests · 2 CLI sessions\n"
          "Tokens: 12,345 in / 678 out\n\n"
          "xai/grok-4.7 · 10 events · "
          "$1.23 reported · $0.0235 estimated · 1 unpriced\n\n"
          "More in Explore data."))

(fact "missing prices and counters never become free or zero"
  (let [data (assoc report :groups []
               :totals (assoc totals :cost_unknown_events 12
                                    :reported_usd 0M :estimated_usd 0M
                                    :input_count nil :output_count nil))
        text (usage/readable data)]
    (string/includes? text "Cost unavailable · 12 unpriced") => true
    (string/includes? text "Tokens unavailable") => true
    (string/includes? text "$0.00") => false))

(fact "zero known cost and tiny positive amounts remain distinguishable"
  (let [data (assoc report :groups []
               :totals (assoc totals :cost_unknown_events 0
                                    :reported_usd 0M :estimated_usd 0M))]
    (string/includes? (usage/readable data) "$0.00 known cost") => true
    (string/includes? (usage/readable
                        (assoc-in data [:totals :reported_usd] 0.0000000007M))
                      "$0.0000000007 reported") => true))

(fact "empty reports keep the relevant coverage warning, not a wall of zeroes"
  (let [data (assoc report :groups []
               :totals (assoc totals :events 0)
               :coverage {:status "partial"
                          :started_at "2026-11-06T16:40:56.858401Z"})]
    (usage/readable data)
    => (str "Yetibot AI usage · Nov 2026 UTC\nNo recorded usage.\n\n"
            "Tracking since Nov 6. Earlier usage is unknown.\n"
            "More in Explore data.")
    (string/includes? (usage/readable
                        (assoc-in data [:coverage :status] "unknown"))
                      "Tracking coverage unknown.") => true))

(fact "only five model groups are shown, ranked by known spend then activity"
  (let [data (assoc report :groups
               (mapv #(assoc totals :provider "fixture" :model (str "model-" %)
                                    :events % :scope "request"
                                    :reported_usd (bigdec %) :estimated_usd 0M)
                     (range 1 21)))
        text (usage/readable data)
        rows (filter #(string/starts-with? % "fixture/")
                     (string/split-lines text))]
    (count rows) => 5
    (mapv #(first (string/split % #" · ")) rows)
    => ["fixture/model-20" "fixture/model-19" "fixture/model-18"
        "fixture/model-17" "fixture/model-16"]
    (string/includes? text "Showing 5 of 20 model groups.") => true
    (string/includes? (usage/readable (assoc data :groups_truncated true))
                      "Showing 5 of 20+ model groups; totals include all.")
    => true
    (get-in data [:totals :events]) => 12))

(fact "media units remain short and singular counts read naturally"
  (let [text (usage/readable (-> report
                                (assoc :groups [])
                                (assoc-in [:totals :images] 1)
                                (assoc-in [:totals :seconds] 8)))]
    (string/includes? text "1 image · 8 video seconds") => true))

(fact "custom instants, failures, CLI scope and incomplete tokens stay visible"
  (let [data (-> report
                 (assoc :window {:start "2026-11-01T01:02:03.123456789Z"
                                 :end "2026-11-08T00:00:00Z"})
                 (assoc-in [:totals :failed_events] 2)
                 (assoc-in [:totals :uncertain_events] 1)
                 (assoc-in [:totals :text_counter_unknown_events] 1)
                 (assoc-in [:groups 0 :scope] "session"))
        text (usage/readable data)]
    (string/includes? text
      "2026-11-01T01:02:03.123456789Z → 2026-11-08 UTC (end exclusive)") => true
    (string/includes? text "2 failed · 1 pending/unknown") => true
    (string/includes? text "Tokens: 12,345 in / 678 out (partial)") => true
    (string/includes? text "xai/grok-4.7 [CLI]") => true))
