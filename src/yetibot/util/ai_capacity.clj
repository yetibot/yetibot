(ns yetibot.util.ai-capacity
  "Closed checks against advertised generation bounds, not token estimates.")

(defn token-count [value]
  (when (and (or (integer? value) (string? value))
             (re-matches #"[1-9][0-9]{0,9}" (str value)))
    (let [n (Long/parseLong (str value))]
      (when (<= n 1000000000) n))))

(defn generation [record allowance]
  (let [context (token-count (get-in record [:limits :context_window :value]))
        outputs (keep token-count
                      [(get-in record [:limits :max_output_tokens :value])
                       (get-in record [:schema-summary :token-parameters
                                       :max_tokens :maximum])
                       (get-in record [:schema-summary :token-parameters
                                       :max_output_tokens :maximum])])
        output (when (seq outputs) (apply min outputs))]
    (cond-> {:generation_limit allowance
             ;; Even a nonempty chat input needs room in the total window.
             :status (cond
                       (or (and context (>= allowance context))
                           (and output (> allowance output))) "incompatible"
                       (or context output) "within-known-limits"
                       :else "unknown")
             ;; Passing these necessary bounds is not a full tokenizer proof.
             :input_fit "not-verified"}
      context (assoc :context_limit context)
      output (assoc :output_limit output))))

(defn check! [record allowance]
  (let [{:keys [status context_limit output_limit] :as result}
        (generation record allowance)]
    (when (= "incompatible" status)
      (throw (ex-info
               (str "The generation allowance " allowance
                    " cannot fit this model's advertised capacity"
                    (when context_limit (str "; total context " context_limit))
                    (when output_limit (str "; output maximum " output_limit))
                    ". Choose a compatible model or explicitly request a "
                    "smaller allowance. No inference was sent.")
               {:ai/error true :ai/request-not-sent true
                :ai/error-code :model-capacity-exceeded
                :ai/response {:max_output_tokens allowance}
                :ai/capacity result})))
    result))
