(ns yetibot.test.integration.ai-usage-report
  (:require [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [taoensso.timbre :as log]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as command]
            [yetibot.core.db.ai-usage :as store]
            [yetibot.core.adapters.discord-inspection :as inspection]
            [yetibot.core.models.admin :as admin]
            [yetibot.core.util.public-result :as public]
            [yetibot.test.support.ai-execution :as execution])
  (:import [java.time Instant]
           [java.sql Timestamp]))

(def now (Instant/parse "2026-11-05T23:59:59Z"))

(defn window [text]
  (if-let [f (try (requiring-resolve 'yetibot.models.ai-usage/window)
                 (catch Exception _ nil))]
    (f text now) ::unimplemented))

(defn interval [start end]
  {:start (Instant/parse start) :end (Instant/parse end)})

(fact "default and explicit calendar months are UTC, including leap years"
  (window "") => (interval "2026-11-01T00:00:00Z" "2026-12-01T00:00:00Z")
  (window "2028-02") => (interval "2028-02-01T00:00:00Z" "2028-03-01T00:00:00Z")
  (window " 2026-12 ") => (interval "2026-12-01T00:00:00Z" "2027-01-01T00:00:00Z"))

(fact "arbitrary date/offset windows have inclusive start and exclusive end"
  (window "2026-10-31 2026-11-02")
  => (interval "2026-10-31T00:00:00Z" "2026-11-02T00:00:00Z")
  (window "2026-03-08T01:00:00-07:00 2026-03-08T03:00:00-06:00")
  => (interval "2026-03-08T08:00:00Z" "2026-03-08T09:00:00Z"))

(fact "invalid or timezone-ambiguous ranges do not become inference prompts"
  (doseq [text ["2026-13" "2026-02-30 2026-03-01" "2026-12-01 2026-11-01"
               "2026-11-01 2026-11-01" "2026-11-01T10:00 2026-11-02T10:00"
               "PRIVATE-GARBAGE" "2026-11 extra extra"]]
    (fact (try (window text) :accepted (catch Exception _ :rejected))
          => :rejected)))

(def totals
  {:events 2 :http_attempts 2 :cli_sessions 0 :failed_events 0
   :uncertain_events 1 :http_requests 1 :cli_requests 0
   :request_count_unknown_events 1 :cost_unknown_events 1
   :reported_usd 0.0000000007M :estimated_usd 0M
   :input_tokens 3 :output_tokens 2 :token_unknown_events 1})

(def fixture
  {:coverage_started_at (Timestamp/from (Instant/parse "2026-11-06T00:00:00Z"))
   :totals (assoc totals :raw_event "PRIVATE-RAW")
   :groups [(assoc totals :provider "fixture" :model "one" :scope "request"
                          :prompt "PRIVATE-PROMPT" :event_id "PRIVATE-EVENT")]
   :groups_truncated false :raw_event "PRIVATE-EVENT"})

(defn with-report [f]
  (let [reads (atom 0)
        forbidden (fn [& _] (throw (AssertionError. "No external IO/inference")))]
    (with-redefs [store/report! (fn [& _] (swap! reads inc) fixture)
                  ai/config (constantly {:providers [{:name "fixture"
                                                      :model "one"
                                                      :key "PRIVATE-KEY"}]})
                  mcp/config (constantly {:servers [] :toolsets []})
                  ai/chat forbidden http/get forbidden http/post forbidden
                  mcp/connect! forbidden mcp/call-tool forbidden
                  admin/user-is-admin? (constantly false)]
      (f reads))))

(fact "ordinary trusted humans get allowlisted frozen projections, not admin gates"
  (with-report
    (fn [reads]
      (execution/in-event execution/request "ai usage 2026-11"
        (fn [event]
          (let [result (command/usage-cmd (assoc event :match ["usage" "2026-11"]))
                data (:result/data result)]
            @reads => 1
            (:scope data) => "yetibot"
            (get-in data [:coverage :status]) => "partial"
            (get-in data [:coverage :provider_account_usage]) => false
            (:result/public-data (public/projection result)) => data
            (string/includes? (pr-str result) "PRIVATE") => false
            (string/includes? (:result/value result) "$0.0000000007") => true
            (string/includes? (:result/value result) "Earlier usage is unknown")
            => true))))))

(fact "frozen report counters survive without relaxing private-key redaction"
  (with-report
    (fn [reads]
      (execution/in-event execution/request "ai usage 2026-11"
        (fn [event]
          (let [result (command/usage-cmd (assoc event :match ["usage" "2026-11"]))
                data (:result/public-data (public/projection result))
                state (atom (inspection/initial))]
            (inspection/capture! state {:data? true :public-data? true
                                         :public-data data})
            (swap! state assoc :sealed? true)
            (get-in @state [:data 0 "totals" "input_count"]) => 3
            (get-in @state [:data 0 "totals" "output_count"]) => 2
            (get-in @state [:data 0 "totals" "text_counter_unknown_events"]) => 1
            (get-in @state [:data 0 "totals" "reported_usd"])
            => 0.0000000007M
            (string/includes? (pr-str @state) "PRIVATE") => false
            (with-redefs [store/report!
                          (fn [& _] (throw (AssertionError. "Frozen; no SQL")))]
              (some? (inspection/payload state ["1" "2"] "d" 0 0 nil)) => true
              (some? (inspection/inline-data-payload state ["1" "2"]
                       {:flags 32768
                        :components [{:type 17 :components
                                      [{:type 10 :content "report"}]}]}
                       nil nil)) => true)
            @reads => 1))))))

(fact "before-rollout windows never claim zero historical usage"
  (with-report
    (fn [_]
      (let [result (execution/run-command execution/request "ai usage 2026-10")]
        (get-in result [:data :coverage :status]) => "before-tracking"
        (string/includes? (:value result) "does not mean zero usage") => true))))

(fact "forged and bot requests fail before any report query"
  (with-report
    (fn [reads]
      (get-in (command/usage-cmd {:match ["usage" nil]})
              [:result/data :isError]) => true
      (doseq [request [(assoc-in execution/request [:user :yetibot?] true)
                       (assoc-in execution/request [:chat-source :raw-event :bot_id]
                                 "BOT")]]
        (fact
          (let [result (execution/run-command request "ai usage 2026-11")]
            (get-in result [:data :isError]) => true
            (get-in result [:data :totals]) => nil
            (string/includes? (:value result) "trusted chat") => true)))
      (execution/in-event execution/request "ai usage"
        (fn [event]
          (get-in (command/usage-cmd (assoc-in event [:user :id] "forged"))
                  [:result/data :isError]) => true))
      @reads => 0)))

(fact "invalid report arguments stay static and never become paid prompts"
  (let [logs (atom [])]
    (log/with-config
      (assoc log/*config* :level :trace :middleware []
             :appenders {:capture {:enabled? true :async? false
                                   :fn #(swap! logs conj (:vargs %))}})
      (with-report
        (fn [reads]
          (let [result (execution/run-command execution/request
                         "ai usage PRIVATE-GARBAGE | ai")
                text (or (:error result) (:value result) "")]
            (string/includes? text "Usage: ai usage") => true
            (string/includes? text "PRIVATE") => false
            @reads => 0))))
    (string/includes? (pr-str @logs) "PRIVATE") => false))
