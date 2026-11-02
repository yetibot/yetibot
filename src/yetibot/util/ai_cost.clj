(ns yetibot.util.ai-cost
  "Public parent-request accounting, never prompts, reasoning or tool payloads."
  (:require [yetibot.api.cloudflare :as cf]
            [yetibot.api.opencode :as zen])
  (:import [java.net URI]
           [java.util Locale]))

(defn- amount? [x]
  (and (number? x) (Double/isFinite (double x)) (<= 0 x 1000000000)))

(defn- tokens? [x]
  (and (integer? x) (<= 0 x 1000000000000)))

(defn- xai? [provider]
  (try
    (let [u (URI. ^String (:url provider))]
      (and (= "https" (.getScheme u)) (= "api.x.ai" (.getHost u))
           (#{-1 443} (.getPort u)) (#{"/v1" "/v1/"} (.getPath u))
           (not (.getUserInfo u)) (not (.getQuery u)) (not (.getFragment u))))
    (catch Exception _ false)))

(defn- token-price [input output cached]
  ;; Official Grok4.7 rates, checked 2026-11-01. USD per million tokens.
  ;; https://docs.x.ai/developers/models/grok-4.7
  (when (and (every? tokens? [input output cached]) (<= cached input))
    (let [[in cache out] (if (>= input 200000) [4M 1M 12M] [2M 0.5M 6M])]
      (/ (+ (* (- input cached) in) (* cached cache) (* output out)) 1000000M))))

(defn- workers-ai? [provider]
  (try
    (let [u (URI. ^String (:url provider))]
      (and (cf/provider-account provider)
           (not (.getUserInfo u)) (not (.getQuery u)) (not (.getFragment u))))
    (catch Exception _ false)))

(defn- workers-usage [model usage]
  (let [input (:prompt_tokens usage) output (:completion_tokens usage)
        neurons (:neurons usage)]
    (cond
      ;; Official USD/M rates checked 2026-11-01. No pricing IO on chat paths.
      ;; developers.cloudflare.com/workers-ai/models/gemma-4-26b-a4b-it/
      (and (= model "@cf/google/gemma-4-26b-a4b-it")
           (every? tokens? [input output]))
      {:usd (/ (+ (* input 0.10M) (* output 0.30M)) 1000000M)
       :kind :estimate :scope :tokens :turns 1}
      (amount? neurons)
      {:neurons neurons :kind :reported :scope :neurons :turns 1}
      (every? tokens? [input output])
      {:input-tokens input :output-tokens output
       :kind :reported :scope :token-usage :turns 1})))

(defn turn [provider model body]
  (let [usage (when (map? (:usage body)) (:usage body))]
    (cond
      (workers-ai? provider) (workers-usage model usage)
      (and (xai? provider) (contains? usage :cost_in_usd_ticks))
      (let [ticks (:cost_in_usd_ticks usage)]
        (when (and (integer? ticks) (<= 0 ticks 1000000000000000000))
          {:usd (/ (bigdec ticks) 10000000000M)
           :kind :reported :scope :api :turns 1}))
      (and (xai? provider) (= "grok-4.7" model))
      (when-let [usd (token-price (or (:input_tokens usage) (:prompt_tokens usage))
                                 (or (:output_tokens usage)
                                     (:completion_tokens usage))
                                 (or (get-in usage [:input_tokens_details
                                                   :cached_tokens])
                                     (get-in usage [:prompt_tokens_details
                                                   :cached_tokens]) 0))]
        {:usd usd :kind :estimate :scope :tokens :turns 1})
      (and (= "zen" (:name provider)) (zen/provider? provider)
           (contains? usage :cost))
      (when (and (amount? (:cost usage))
                 (contains? #{nil true false}
                            (get-in usage [:opencode :estimated])))
        {:usd (bigdec (:cost usage))
         :kind (if (true? (get-in usage [:opencode :estimated]))
                 :estimate :reported)
         :scope :api :turns 1})
      (and (= "zen" (:name provider)) (zen/provider? provider)
           (true? (:ai/verified-free provider)))
      {:usd 0M :kind :estimate :scope :api :turns 1})))

(def initial {:usd 0M :kind :reported :scope :api :turns 0})

(defn add [total next-turn]
  ;; Missing turns or incompatible scopes make the total unknown, not a subtotal.
  (when (and total next-turn
             (or (zero? (:turns total)) (= (:scope total) (:scope next-turn))))
    (reduce
      (fn [result key]
        (if (contains? next-turn key)
          (update result key + (get total key 0)) result))
      (assoc next-turn :kind (if (or (= :estimate (:kind total))
                                     (= :estimate (:kind next-turn)))
                               :estimate :reported))
      [:usd :neurons :input-tokens :output-tokens :turns])))

(defn visible-estimate
  "Stable live-Grok estimate of visible text only, not the final API bill.
   UTF-8 bytes/4 approximates tokens. Excludes reasoning, tool fees, inserted
   host instructions and delegated calls. Do not estimate multimodal/opaque input."
  [provider model messages text]
  (when (and (xai? provider) (= "grok-4.7" model)
             (seq messages) (string? text)
             (every? #(and (map? %) (string? (:content %))
                           (not (:encrypted_content %))) messages))
    (let [estimate #(long (Math/ceil (/ (alength (.getBytes ^String % "UTF-8"))
                                      4.0)))
          input (reduce + (map #(+ 4 (estimate (:content %))) messages))]
      (when-let [usd (token-price input (estimate text) 0)]
        {:usd usd :kind :estimate :scope :visible-text :turns 1}))))

(defn label [cost]
  (cond
    (and (= :reported (:kind cost)) (= :neurons (:scope cost))
         (amount? (:neurons cost)))
    (String/format Locale/ROOT "%.2f neurons"
                   (to-array [(double (:neurons cost))]))
    (and (= :reported (:kind cost)) (= :token-usage (:scope cost))
         (every? tokens? [(:input-tokens cost) (:output-tokens cost)]))
    (str (:input-tokens cost) " input / " (:output-tokens cost) " output tokens")
    (and (map? cost) (amount? (:usd cost))
         (#{:reported :estimate} (:kind cost))
         (#{:api :tokens :visible-text} (:scope cost)))
    (let [usd (:usd cost)
          amount (if (< 0 usd 0.0001M) "<$0.0001"
                   (str "$" (String/format Locale/ROOT
                               (if (< usd 0.01M) "%.4f" "%.2f")
                               (to-array [(double usd)]))))]
      (str (case (:scope cost)
             :visible-text "text est. "
             :tokens "tokens est. "
             :api (if (= :estimate (:kind cost)) "API est. " "API "))
           amount))
    :else "cost n/a"))
