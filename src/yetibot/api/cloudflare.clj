(ns yetibot.api.cloudflare
  "Bounded Cloudflare JSON transport; no model-selected hosts or credentials."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string])
  (:import [java.io InputStream ByteArrayOutputStream]
           [java.net URI]
           [org.apache.http.client.methods HttpUriRequest]))

(defn fail! [message]
  (throw (ex-info message {:ai/error true})))

(defn read-bytes
  ([stream limit] (read-bytes stream limit 60000))
  ([^InputStream stream limit timeout-ms]
   (let [deadline (+ (System/nanoTime) (* 1000000 timeout-ms))
         buffer (byte-array 8192)
         output (ByteArrayOutputStream.)]
     (loop [total 0]
       (when (>= (System/nanoTime) deadline)
         (fail! "Input or response exceeded its read deadline."))
       (let [n (.read stream buffer 0 (min 8192 (- (inc limit) total)))]
         (when (>= (System/nanoTime) deadline)
           (fail! "Input or response exceeded its read deadline."))
         (when (> (+ total (max 0 n)) limit)
           (fail! "Cloudflare input or response exceeded its size limit."))
         (if (neg? n) (.toByteArray output)
           (do (.write output buffer 0 n) (recur (+ total n)))))))))

(defn consume-response [response callback]
  (let [^InputStream stream (:body response)]
    (try
      (callback stream)
      (finally
        ;; Abort before closing: Apache may otherwise drain an unbounded body.
        (when-let [request (get-in response [:request :http-req])]
          (.abort ^HttpUriRequest request))
        (try (when stream (.close stream)) (catch Exception _ nil))))))

(defn request! [method url options]
  (try
    (let [response (method url
                           (merge {:as :stream :throw-exceptions false
                                   :save-request true
                                   :conn-timeout 5000 :socket-timeout 60000
                                   :redirect-strategy :none
                                   :retry-handler (fn [& _] false)} options))]
      (consume-response response
        (fn [stream]
          (when-not (<= 200 (:status response) 299)
            (fail! (str "Cloudflare returned HTTP " (:status response) ".")))
          (let [body (json/parse-string
                       (String. ^bytes (read-bytes stream 1048576) "UTF-8")
                       true)]
            (when-not (and (true? (:success body))
                           (empty? (:errors body)))
              (fail! "Cloudflare returned an unsuccessful response."))
            body))))
    (catch Exception e
      (if (:ai/error (ex-data e)) (throw e)
        (fail! "Cloudflare request failed or returned invalid JSON.")))))

(defn account-url [account]
  (when-not (and (string? account) (re-matches #"[a-fA-F0-9]{32}" account))
    (fail! "Configure ai.intent-router.account-id and key-file first."))
  (str "https://api.cloudflare.com/client/v4/accounts/" account))

(defn gateway-headers [gateway]
  (when (and gateway
             (not (and (string? gateway)
                       (re-matches #"[a-zA-Z0-9_-]{1,64}" gateway))))
    (fail! "Invalid Cloudflare gateway ID."))
  (if gateway {"cf-aig-gateway-id" gateway} {}))

(defn- probability? [value]
  (and (number? value) (Double/isFinite (double value)) (<= 0 value 1)))

(defn- probabilities? [expected values]
  (and (map? values) (= (set expected) (set (keys values)))
       (every? probability? (vals values))
       (< (Math/abs (- 1.0 (reduce + (vals values)))) 0.001)))

(defn- field-value? [schema value]
  (or (nil? value)
      (case (:type schema)
        ("number" "duration")
        (and (number? value) (Double/isFinite (double value)))
        ("text" "date" "time" "datetime") (string? value)
        "list" (and (vector? value)
                    (every? #(field-value? {:type (:items schema)} %) value))
        "estimate" (if (string? (:min schema)) (string? value)
                     (field-value? {:type "number"} value))
        "forecast" (and (vector? value)
                        (every?
                          #(field-value? (assoc schema :type "estimate") %)
                          value))
        false)))

(defn- valid-answer? [model schema answer]
  (and (= (:type schema) (:type answer))
       (or (not (contains? answer :confidence))
           (probability? (:confidence answer)))
       (if (= model "clairic")
         (and (contains? answer :value)
              (field-value? schema (:value answer))
              (or (not (contains? answer :p_null))
                  (probability? (:p_null answer))))
         (case (:type schema)
           "noul" (probability? (:noul answer))
           "choice" (and (string? (:choice answer))
                         (contains? (:criteria schema)
                                    (keyword (:choice answer)))
                         (probabilities? (keys (:criteria schema))
                                         (:probabilities answer))
                         (= (get (:probabilities answer)
                                 (keyword (:choice answer)))
                            (apply max (vals (:probabilities answer))))
                         (probability? (:confidence answer)))
           "score" (and (number? (:score answer))
                        (<= 0 (:score answer) (dec (count (:criteria schema))))
                        (probabilities?
                          (map #(keyword (str %))
                               (range (count (:criteria schema))))
                          (:probabilities answer))
                        (probability? (:confidence answer)))
           false))))

(defn infer! [config token model payload]
  (when-not (#{"clef" "clairic"} model)
    (fail! "Unsupported structured inference model."))
  (let [body (json/generate-string (assoc payload :model model))]
    (when (string/includes? body token)
      (fail! "Inference input contains the configured credential."))
    (let [response (request!
                     http/post
                     (str (account-url (:account-id config))
                          "/ai/run/@cf/cloudflare/" model)
                     {:headers (merge {"Authorization" (str "Bearer " token)}
                                      (gateway-headers (:gateway-id config)))
                      :content-type :json :body body})
          result (:result response)
          schema (or (:questions payload) (:fields payload))
          answers (:answers result)]
      (when-not (and (#{model (str "@cf/cloudflare/" model)} (:model result))
                     (or (not (contains? result :truncated))
                         (boolean? (:truncated result)))
                     (map? answers)
                     (= (set (keys schema)) (set (keys answers)))
                     (every? (fn [[id answer]]
                               (and (map? answer)
                                    (valid-answer?
                                      model (get schema id) answer)))
                             answers))
        (fail! "Cloudflare returned invalid structured inference results."))
      (when (string/includes? (json/generate-string result) token)
        (fail! "Cloudflare returned sensitive output."))
      result)))

(defn provider-account [provider]
  (let [uri (URI. (:url provider))]
    (when (and (= "https" (.getScheme uri))
               (.equalsIgnoreCase "api.cloudflare.com" (.getHost uri))
               (#{-1 443} (.getPort uri)))
      (second (re-matches
                #"/client/v4/accounts/([a-fA-F0-9]{32})/ai/v1/?"
                (.getPath uri))))))

(defn catalog [provider]
  (let [account (provider-account provider)]
    (loop [page 1 records []]
      (when (> page 20)
        (fail! "Cloudflare model catalog exceeded 20 pages."))
      (let [body (request! http/get
                           (str (account-url account) "/ai/models/search")
                           {:headers {"Authorization"
                                      (str "Bearer " (:key provider))}
                            :query-params {:page page :per_page 100}})
            batch (:result body)
            info (:result_info body)]
        (when-not (and (vector? batch) (<= (count batch) 100)
                       (every? #(and (map? %)
                                     (string? (:name %))
                                     (re-matches #"@[^\s/]+/[^\s/]+/[^\s]+"
                                                 (:name %))) batch))
          (fail! "Cloudflare returned an invalid model catalog."))
        (let [records (into records batch)
              more? (if (number? (:total_pages info))
                      (< page (:total_pages info))
                      (= 100 (count batch)))]
          (if more? (recur (inc page) records)
            {:source :cloudflare-workers-ai
             :coverage
             "Workers AI only; third-party Gateway models not included"
             :models (->> records
                          (map #(hash-map
                                  :id (:name %) :task (:task %)
                                  :interface
                                  (cond
                                    (string/starts-with? (:name %)
                                                        "@cf/cloudflare/clef")
                                    :decision
                                    (= "@cf/cloudflare/clairic" (:name %))
                                    :extraction
                                    :else :model-specific)))
                          (reduce #(assoc %1 (:id %2) %2) {})
                          vals (sort-by :id) vec)}))))))
