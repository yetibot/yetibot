(ns yetibot.test.models.ai-tool-trace
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.models.ai-tool-trace :as trace]))

(def answer {:provider "zen" :model "gpt-6-luna" :text "323"
             :ai/cost {:usd 0.00001M :kind :reported :scope :api}})
(def report
  {:reason :partial-result :budget {:limit 2 :used 2 :requested 2}
   :calls [{:call 1 :operation :inference :outcome :success}
           {:call 2 :operation :inference :outcome :error
            :model "deepseek-v4.1-flash" :code :output-limit
            :output_count 16 :thinking_count 16 :output-cap 16 :elapsed_ms 691}]
   :omitted 0 :truncated false})

(fact "partial model replies lead with actual answers, not diagnostics"
  (trace/summary report [answer])
  => "323\n-# zen/gpt-6-luna\n\n1 answered · 1 hit its output limit.")

(fact "all failed replies are short without pretending there was an answer"
  (trace/summary (assoc report :calls [(second (:calls report))]) [])
  => "No model answers returned.\n\n1 hit its output limit.")

(fact "uncertainty and skipped calls remain explicit below any obtained answer"
  (let [r (assoc report :reason :unknown-result
                :calls [{:operation :inference :outcome :unknown}
                        {:outcome :skipped}])
        text (trace/summary r [answer])]
    (string/starts-with? text "323\n") => true
    (string/includes? text "1 answered · 1 unconfirmed · 1 not run.") => true
    (string/includes? text "Do not retry unconfirmed calls automatically.") => true))

(facts "plural outcomes, shortening and non-model failures stay truthful"
  (trace/summary (assoc report :calls (vec (repeat 2 (second (:calls report))))) [])
  => "No model answers returned.\n\n2 hit their output limit."
  (string/includes? (trace/summary report [(assoc answer :truncated true)])
                    "323 [shortened]") => true
  (string/includes?
    (trace/summary (assoc report :calls [{:outcome :error}]) [answer])
    "1 tool failed") => true
  (string/includes?
    (trace/summary (assoc report :truncated true) [answer])
    "Some execution details were omitted.") => true)

(fact "technical receipts remain in frozen execution, not ordinary chat"
  (let [text (trace/summary report [answer])
        data (trace/public-report report)]
    (boolean (re-find #"thinking=|output=|cap=|691ms|Tool budget|API|Consumed requests"
                     text)) => false
    (get-in data [:calls 1 :output-cap]) => 16
    (get-in data [:calls 1 :thinking_count]) => 16
    (get-in data [:calls 1 :elapsed_ms]) => 691))
