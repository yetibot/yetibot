(ns yetibot.models.ai-pricing
  "Reviewed public list-price comparisons; never account balances or bills."
  (:require [yetibot.api.opencode :as zen]
            [yetibot.util.ai-cost :as cost]))

(def unavailable {:status "unavailable"})

(defn- zen? [provider]
  (and (= "zen" (:name provider)) (zen/provider? provider)))

(defn catalog [providers]
  (when (some zen? providers)
    (try (zen/model-prices) (catch Exception _ nil))))

(defn comparison [previous current prices]
  (let [price #(or (if (zen? %) (get prices (:model %))
                       (cost/published-rates % (:model %))) unavailable)
        before (price previous) after (price current)]
    {:previous before :current after
     :difference (when (and (= "available" (:status before))
                            (= "available" (:status after)))
                   {:input (- (bigdec (:input after)) (bigdec (:input before)))
                    :output (- (bigdec (:output after))
                               (bigdec (:output before)))})}))

(defn- amount [value]
  (.toPlainString (.stripTrailingZeros (bigdec value))))

(defn- rate-text [price]
  (if (= "available" (:status price))
    (str "$" (amount (:input price)) " input / $"
         (amount (:output price)) " output")
    "unavailable"))

(defn summary
  [{:keys [previous current difference]} previous-choice current-choice]
  (let [label #(str (:provider %) " / " (or (:model %) "automatic"))]
    (str "\nPricing (USD / 1M text tokens, base list rates—not your bill):"
       "\nNew (" (label current-choice) "): " (rate-text current)
       "\nPrevious (" (label previous-choice) "): " (rate-text previous)
       "\nChange (new − previous): "
       (if difference (rate-text (assoc difference :status "available"))
         "unavailable")
       "\nCache, context tiers, media and tools may change the actual cost.")))
