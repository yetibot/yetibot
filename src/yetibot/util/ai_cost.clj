(ns yetibot.util.ai-cost
  "Request-local measured text accounting, never prompts or tool payloads."
  (:require [clojure.string :as string]
            [yetibot.api.cloudflare :as cf]
            [yetibot.api.opencode :as zen]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.core.util.public-result :as public]
            [yetibot.util.ai-public :as ai-public])
  (:import [java.net URI]
           [java.util Locale]))

(def ^:private amount? usage/amount?)
(def ^:private tokens? usage/counter?)

(defn- xai? [provider]
  (try
    (let [u (URI. ^String (:url provider))]
      (and (= "https" (.getScheme u)) (= "api.x.ai" (.getHost u))
           (#{-1 443} (.getPort u)) (#{"/v1" "/v1/"} (.getPath u))
           (not (.getUserInfo u)) (not (.getQuery u)) (not (.getFragment u))))
    (catch Exception _ false)))

(def ^:private grok-rates usage/grok-rates)
(def ^:private gemma-rates [0.10M 0.30M])

(def ^:private token-price usage/grok-token-cost)

(defn- workers-ai? [provider]
  (try
    (let [u (URI. ^String (:url provider))]
      (and (cf/provider-account provider)
           (not (.getUserInfo u)) (not (.getQuery u)) (not (.getFragment u))))
    (catch Exception _ false)))

(defn published-rates
  "Known-origin base list rates, not bills or account-specific discounts."
  [provider model]
  (let [rates (cond
                (and (xai? provider) (= model "grok-4.7"))
                [(first (:short grok-rates)) (last (:short grok-rates))]
                (and (workers-ai? provider)
                     (= model "@cf/google/gemma-4-26b-a4b-it")) gemma-rates)]
    (when rates
      {:status "available" :currency "USD" :unit "1M text tokens"
       :input (first rates) :output (second rates) :base_rates true
       :tiered (= model "grok-4.7")})))

(defn- workers-usage [model usage]
  (let [input (:prompt_tokens usage) output (:completion_tokens usage)
        neurons (:neurons usage)]
    (cond
      ;; Official USD/M rates checked 2026-11-01. No pricing IO on chat paths.
      ;; developers.cloudflare.com/workers-ai/models/gemma-4-26b-a4b-it/
      (and (= model "@cf/google/gemma-4-26b-a4b-it")
           (every? tokens? [input output]))
      {:usd (/ (+ (* input (first gemma-rates))
                  (* output (second gemma-rates))) 1000000M)
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
      {:usd 0M :kind :estimate :scope :api :turns 1}
      (and (= "zen" (:name provider)) (zen/provider? provider)
           (= model (::zen/selected-model provider)))
      (let [{:keys [input output]} (::zen/pricing provider)
            in (or (:input_tokens usage) (:prompt_tokens usage))
            out (or (:output_tokens usage) (:completion_tokens usage))]
        ;; Fresh catalog base rates are partial estimates, not invoices;
        ;; cached tiers, tool fees and discounts are deliberately not invented.
        (when (and (every? amount? [input output])
                   (every? tokens? [in out]))
          (let [usd (/ (+ (* (bigdec in) input) (* (bigdec out) output))
                       1000000M)]
            (when (amount? usd)
              {:usd usd :kind :estimate :scope :tokens :turns 1
               :input-tokens in :output-tokens out})))))))

(defn ledger-metrics
  "Per-wire receipt only. Never record a parent answer's accumulated caption."
  [provider model body]
  (let [cost (turn provider model body)]
    (cond-> (usage/tokens body)
      (and (amount? (:usd cost)) (#{:reported :estimate} (:kind cost)))
      (assoc :usd (:usd cost) :cost_kind (name (:kind cost))))))

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

(def ^:dynamic *observer* nil)
(def ^:dynamic *role* :parent)

(defn observe!
  "One observation per attempted wire request, before output normalization.
   Only bounded monetary data escapes; no response payload reaches observers."
  [provider model body]
  (when *observer*
    (let [value (when body (turn provider model body))]
      (*observer* {:role *role*
                   :cost (when (and (amount? (:usd value))
                                    (#{:reported :estimate} (:kind value)))
                           (select-keys value [:usd :kind :scope]))}))))

(def empty-subtotal
  {:requests 0 :reported_requests 0 :estimated_requests 0
   :reported_usd 0M :estimated_usd 0M :unpriced 0})

(defn capture
  "Nestable: each observation reaches the outer run exactly once. HTTP errors
   remain unpriced, not free. External MCP fees/media/CLI are outside this scope."
  [f]
  (let [outer *observer*
        totals (atom {:scope :parent-and-delegated-text-requests
                      :parent empty-subtotal :delegated empty-subtotal})
        seen? #(pos? (+ (get-in @totals [:parent :requests])
                       (get-in @totals [:delegated :requests])))]
    (binding [*observer*
              (fn [{:keys [role cost] :as receipt}]
                (when outer (outer receipt))
                (let [role (if (= :delegated role) :delegated :parent)]
                  (swap! totals
                         #(-> %
                              (update-in [role :requests] inc)
                              (cond-> cost
                                (update-in
                                  [role (if (= :reported (:kind cost))
                                          :reported_requests :estimated_requests)]
                                  inc))
                              (update-in
                                [role (case (:kind cost)
                                        :reported :reported_usd
                                        :estimate :estimated_usd :unpriced)]
                                + (if cost (:usd cost) 1))))))]
      (try
        (let [result (f)]
          (if-not (seen?) result
            (ai-public/reviewed
              (-> result
                  (assoc-in [:result/data :ai/accounting] @totals)
                  (assoc :result/public-data
                         (assoc (:result/public-data (public/projection result) {})
                                :accounting @totals))))))
        (catch Exception e
          (throw (ex-info (.getMessage e)
                          (cond-> (ex-data e) (seen?)
                            (assoc :ai/accounting @totals)))))))))

(defn accounting-label [accounting]
  (when (= :parent-and-delegated-text-requests (:scope accounting))
    (let [parts
          (for [[role title] [[:parent "parent"] [:delegated "children"]]
                :let [s (get accounting role)] :when (pos? (:requests s 0))]
            (str title " "
                 (string/join
                   " + "
                   (concat
                     (when (pos? (:reported_requests s 0))
                       [(label {:usd (:reported_usd s)
                                :kind :reported :scope :api})])
                     (when (pos? (:estimated_requests s 0))
                       [(label {:usd (:estimated_usd s)
                                :kind :estimate :scope :api})])
                     (when (pos? (:unpriced s 0))
                       [(str (:unpriced s) " unpriced")])))))]
      (if (and (zero? (get-in accounting [:delegated :requests] 0))
               (zero? (get-in accounting [:parent :reported_requests] 0))
               (zero? (get-in accounting [:parent :estimated_requests] 0)))
        "cost n/a"
        (string/join " · " parts)))))
