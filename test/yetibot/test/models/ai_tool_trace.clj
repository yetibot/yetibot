(ns yetibot.test.models.ai-tool-trace
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.models.ai-tool-trace :as trace]
            [yetibot.util.ai-public :as public]
            [yetibot.core.util.public-result :as result]
            [yetibot.core.adapters.discord-inspection :as viewer]))

(def answer {:provider "zen" :model "gpt-6-luna" :text "323"
             :ai/cost {:usd 0.00001M :kind :reported :scope :api}})
(def report
  {:reason :partial-result :budget {:limit 2 :used 2 :requested 2}
   :calls [{:call 1 :operation :inference :outcome :success}
           {:call 2 :operation :inference :outcome :error
            :provider "zen" :model "deepseek-v4.1-flash" :code :output-limit
            :output_count 16 :thinking_count 16 :output-cap 16 :elapsed_ms 691}]
   :omitted 0 :truncated false})

(fact "partial model replies lead with actual answers, not diagnostics"
  (trace/summary report [answer])
  => (str "323\n-# zen/gpt-6-luna\n\n1 answered · 1 hit its output limit."
          "\n\n#2 zen/deepseek-v4.1-flash (inference) — "
          "Generation limit reached before a complete answer."))

(fact "all failed replies are short without pretending there was an answer"
  (trace/summary (assoc report :calls [(second (:calls report))]) [])
  => (str "No model answers returned.\n\n1 hit its output limit."
          "\n\n#2 zen/deepseek-v4.1-flash (inference) — "
          "Generation limit reached before a complete answer."))

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
  => (str "No model answers returned.\n\n2 hit their output limit."
          "\n\n#2 zen/deepseek-v4.1-flash (inference) — "
          "Generation limit reached before a complete answer."
          "\n#2 zen/deepseek-v4.1-flash (inference) — "
          "Generation limit reached before a complete answer.")
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

(fact "a discovery failure keeps the parent's independent terminal answer"
  (let [r (assoc report :calls [{:call 1 :operation :models :outcome :error
                                :provider "zen" :code :policy-denied}])
        text (trace/summary r [] "Actual independent answer.")]
    (string/starts-with? text "Actual independent answer.") => true
    (string/includes? text "policy") => true))

(facts "failure-only snapshots bound rows and surface unknowns before skips"
  (let [r (assoc report :calls (vec (concat
                                    (repeat 8 {:outcome :success})
                                    (for [i (range 9 16)]
                                      {:call i :outcome :error :code :tool-error})
                                    [{:call 16 :outcome :unknown}
                                     {:call 17 :outcome :skipped}])))
        data (trace/public-errors r)]
    (mapv :call (:calls data)) => [16 9 10]
    (:omitted data) => 6
    (:truncated data) => true
    (string/includes? (get-in data [:calls 1 :reason]) "not retained") => true))

(fact "three worst-case rows survive the actual frozen node and text bounds"
  (let [row {:tool (apply str (repeat 64 "x")) :operation :inference
             :provider (apply str (repeat 200 "p"))
             :model (apply str (repeat 200 "m")) :outcome :error
             :code :request-rejected :http_status 422
             :error-class :invalid-request :error-code :unsupported-parameter
             :parameter :token-limit :output-cap 262144
             :output_count 262144 :thinking_count 262144}
        r (assoc report :calls (mapv #(assoc row :call %) [1 2 3]))
        snapshot (atom (viewer/initial))]
    (binding [result/*capture* #(viewer/capture! snapshot %)]
      (result/observe!
        (public/reviewed {:result/value "Partial" :result/data {:isError true}
                          :result/public-error (trace/public-errors r)})))
    (mapv #(get-in % ["details" "thinking_count"])
          (get-in @snapshot [:errors 0 "calls"])) => [262144 262144 262144]
    (get-in @snapshot [:errors 0 "truncated"]) => false
    (get-in @snapshot [:errors 0 "omitted"]) => 0))
