(ns yetibot.api.ai-outcome
  "Closed wire-success/output-failure metadata. Never retains response text."
  (:require [clojure.string :as string]
            [yetibot.api.ai-diagnostics :as diagnostics]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.util.ai-cost :as cost]))

(def messages
  {:output-limit
   (str "The provider reached its output limit. No complete answer was returned. "
        "Reasoning may consume the same generation budget. The request was "
        "consumed; it was not retried and the budget was not increased.")
   :no-answer
   (str "The provider returned no usable text answer. The response was received; "
        "the consumed request was not retried.")
   :unsupported-output
   (str "The provider returned unsupported output. No usable answer was returned; "
        "the consumed request was not retried.")
   :incomplete-output
   (str "The provider returned an incomplete response. No complete answer was "
        "returned; the consumed request was not retried.")})

(defn details [provider model body options]
  (let [response? (= "responses" (:api provider))
        finish (get-in body [:choices 0 :finish_reason])
        status (:status body)
        text? #(and (string? %) (not (string/blank? %)))
        present? (if response?
                   (->> (:output body) (filter #(= "message" (:type %)))
                        (mapcat :content) (filter #(= "output_text" (:type %)))
                        (some #(text? (:text %))))
                   (text? (get-in body [:choices 0 :message :content])))
        counters (usage/tokens body)]
    (merge
      (select-keys
        (diagnostics/context provider
          (if response? "/responses" "/chat/completions")
          {:model model} (::diagnostics/stage options :initial))
        [:provider :model :endpoint :stage])
      {:finish (get {"length" :length "stop" :stop
                     "tool_calls" :tool-calls "content_filter" :content-filter}
                    finish (if (nil? finish) :missing :other))
       :status (get {"completed" :completed "incomplete" :incomplete
                     "failed" :failed} status :other)
       :text-present (boolean present?)}
      (when (and (integer? (:max-output-tokens options))
                 (<= 1 (:max-output-tokens options) 262144))
        {:output-cap (:max-output-tokens options)})
      (into {}
            (keep (fn [[source target]]
                    (let [n (get counters source)]
                      (when (usage/counter? n) [target n]))))
            [[:input_tokens :input_count] [:output_tokens :output_count]
             [:reasoning_tokens :thinking_count]]))))

(defn fail! [code provider model body options]
  (let [record (assoc (details provider model body options) :output-error code)]
    (diagnostics/emit! (assoc record :phase :output))
    (throw (ex-info (get messages code)
                    {:ai/error true :ai/output-error code
                     :ai/response record :ai/cost (cost/turn provider model body)}))))

(defn check! [provider model body options]
  (cond
    (or (= "length" (get-in body [:choices 0 :finish_reason]))
        (and (= "incomplete" (:status body))
             (= "max_output_tokens" (get-in body [:incomplete_details :reason]))))
    (fail! :output-limit provider model body options)
    (and (= "responses" (:api provider))
         (contains? #{"incomplete" "failed"} (:status body)))
    (fail! :incomplete-output provider model body options)
    :else
    (diagnostics/emit-response! (assoc (details provider model body options)
                                      :phase :response))))
