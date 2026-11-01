(ns yetibot.api.opencode
  "OpenCode Zen's catalog lives outside the OpenAI chat API base path."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.net URI]))

(def api-key-placeholder "REPLACE_WITH_OPENCODE_ZEN_API_KEY")

(defn configured-key? [key]
  (and (string? key) (not (string/blank? key))
       (not= api-key-placeholder key)))

(defn provider? [{:keys [url]}]
  (try
    (let [uri (URI. url)]
      (and (= "https" (.getScheme uri))
           (= "opencode.ai" (.getHost uri))
           (#{-1 443} (.getPort uri))
           (not (.getUserInfo uri))
           (not (.getQuery uri))
           (not (.getFragment uri))
           (boolean
             (re-matches #"/(?:inference/openai|zen)/v1/?"
                         (.getPath uri)))))
    (catch Exception _ false)))

(defn catalog-provider [provider]
  ;; Never follow catalog-advertised URLs or redirect credentials to a new
  ;; origin. Legacy Zen URLs keep their existing /models endpoint.
  (if (provider? provider)
    (cond-> provider
      (not (configured-key? (:key provider))) (dissoc :key)
      (.startsWith (.getPath (URI. (:url provider))) "/inference/")
      (assoc :url "https://opencode.ai/inference/v1"))
    provider))

(defn- unavailable! []
  (throw (ex-info (str "Verified-free Zen models are unavailable. "
                       "No paid fallback was used.") {:ai/error true})))

(defn- zero-price? [value]
  (and (map? value) (= #{:price} (set (keys value)))
       (number? (:price value)) (zero? (:price value))))

(defn- free-side? [value]
  (and (map? value) (every? #{:price :cache} (keys value))
       (zero-price? (select-keys value [:price]))
       (or (not (contains? value :cache))
           (let [cache (:cache value)]
             (and (map? cache) (seq cache)
                  (every? #{:read :write} (keys cache))
                  (every? zero-price? (vals cache)))))))

(defn free-model? [{:keys [id cost]}]
  (boolean
    (and (string? id) (re-matches #"[A-Za-z0-9@._:/-]{1,200}" id)
         (map? cost) (= #{:text} (set (keys cost)))
         (map? (:text cost))
         (= #{:input :output} (set (keys (:text cost))))
         (every? free-side? (vals (:text cost))))))

(defn- catalog-body [^InputStream stream started]
  (let [out (ByteArrayOutputStream.) buffer (byte-array 4096)]
    (loop [total 0]
      (when (or (.isInterrupted (Thread/currentThread))
                (> (- (System/nanoTime) started) 5000000000))
        (unavailable!))
      (let [n (.read stream buffer)]
        (if (neg? n)
          (.toString out "UTF-8")
          (do (when (> (+ total n) 524288) (unavailable!))
              (.write out buffer 0 n)
              (recur (+ total n))))))))

(defn free-models
  "Fresh catalog verification, not proof of inference entitlement. No retries."
  [provider]
  (when-not (and (= "zen" (:name provider)) (provider? provider))
    (unavailable!))
  (try
    (let [provider (catalog-provider provider)
          started (System/nanoTime)
          response (http/get (str (string/replace (:url provider) #"/+$" "")
                                  "/models")
                     {:as :stream :throw-exceptions false
                      :conn-timeout 1500 :socket-timeout 1500
                      :connection-request-timeout 1000
                      :redirect-strategy :none :follow-redirects false
                      :retry-handler (fn [& _] false)
                      :headers (cond-> {"Accept" "application/json"}
                                 (:key provider)
                                 (assoc "Authorization"
                                        (str "Bearer " (:key provider))))})]
      (with-open [stream (:body response)]
        (when-not (and (= 200 (:status response))
                       (instance? InputStream stream)) (unavailable!))
        (let [models (:data (json/parse-string
                              (catalog-body stream started) true))]
          (when-not (and (vector? models) (<= (count models) 8192)
                         (every? #(and (map? %) (string? (:id %))) models)
                         (= (count models) (count (distinct (map :id models)))))
            (unavailable!))
          (->> models (filter free-model?) (map :id)
               (remove #(and (configured-key? (:key provider))
                             (string/includes? % (:key provider))))
               sort vec))))
    (catch Exception _ (unavailable!))))

;; Preference only; never credentials or file configuration. Every use is
;; revalidated against current prices, so expired offers cannot stick.
(defonce preferences (atom {}))

(defn default-model [provider allowed?]
  (let [eligible (filterv allowed? (free-models provider))
        preferred (get @preferences "zen")]
    (or (some #{preferred} eligible) (first eligible) (unavailable!))))

(defn set-default! [provider model allowed?]
  (when-not (and (some #{model} (free-models provider)) (allowed? model))
    (unavailable!))
  (swap! preferences assoc "zen" model)
  {:provider "zen" :model model :runtime_only true :verified_free true})
