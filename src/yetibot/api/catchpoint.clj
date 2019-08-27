(ns yetibot.api.catchpoint
  "Catch point API interface. See Catchpoint docs at:
   https://io.catchpoint.com/ui/help"
  (:require
    [clojure.core.async :refer [go-loop timeout <!]]
    [clojure.spec.alpha :as s]
    [clj-http.client :as client]
    [clj-http.util :refer [utf8-bytes base64-encode]]
    [taoensso.timbre :refer [warn info]]
    [yetibot.core.spec :as yspec]
    [yetibot.core.config :refer [get-config]]
    [clojure.core.strint :refer [<<]]
    ))

;; based on https://gist.github.com/jmervine/837bdd303798ad122fda
(def host "https://io.catchpoint.com")
(def prefix "/ui/api")
(def version 1)

(defn token-uri [] (<< "~{host}~{prefix}/token"))

(s/def ::key ::yspec/non-blank-string)

(s/def ::secret ::yspec/non-blank-string)

(s/def ::config (s/keys :req-un [::key ::secret]))

(defn config []
  (let [c (get-config ::config [:catchpoint])]
    (if-let [{:keys [value]} c]
      value
      (warn "Error obtaining config for Catchpoint" c))))

(defn fetch-token []
  (let [response (client/post
                   (token-uri)
                   {:as :json
                    :headers {:accept "*/*"}
                    :form-params {:grant_type "client_credentials",
                                  :client_id (:key (config))
                                  :client_secret (:secret (config))}})]
    (:body response)))

;; token expires every 30 minutes. refresh it on every API call for now.
(defonce token (atom nil))

(defn refresh-token! []
  (reset! token (:access_token (fetch-token))))

(def refresh-interval-ms (* 10 60000)) ;; 10 minutes

;; token refresh poller

(defonce refresh-loop
  (when (config)
    (go-loop [refresh-count 1]
             (refresh-token!)
             (info "Refreshing Catchpoint token:" refresh-count)
             (<! (timeout refresh-interval-ms))
             (recur (inc refresh-count)))))

(defn headers []
  (let [encoded-token (-> @token utf8-bytes base64-encode)]
    {"Authorization" (<< "Bearer ~{encoded-token}")}))

(defn api-uri [endpoint]
  (<< "~{host}~{prefix}/v~{version}/~{endpoint}"))

;; formatters

(defn format-test-data
  [{test-name :name
    description :description
    start-date :start
    change-date :change_date
    test-url :test_url
    {test-type :name} :test_type
    {status :name} :status
    {simulate :name} :simulate
    :as test-data}]
  [(<< "*~{test-name}*")
   description
   (<< "🕸  Test UR: ~{test-url}")
   (<< "🕐 Start date: *~{start-date}* 🕣 Change date: *~{change-date}*")
   (<< "⚙️  Status: *~{status}* ⚙️ Test type: *~{test-type}*")])

;; usage

(defn fetch
  ([endpoint] (fetch endpoint {}))
  ([endpoint {:keys [query-params]}]
   (client/get
     (api-uri endpoint)
     {:accept "*/*"
      :as :json
      :query-params query-params
      :headers (headers)})))

(defn tests
  []
  (if-let [{{items :items}:body} (fetch "tests")]
    {:result/data items
     :result/value
     (into {} (map (fn [{item-name :name id :id}] [(str id) item-name]) items))}
    {:result/error "Error fetching tests from catchpoint"}))

(defn test-by-id
  [id]
  (fetch (<< "tests/~{id}")))

(defn resolve-tests
  "Given a string, match it against known tests and retrieve test ID for the
   first match"
  [test-to-match]
  (let [test-pattern (re-pattern (<< "(?i)~{test-to-match}"))
        {tests :result/value} (tests)]
    (into {} (filter
               (fn [[id test-name]] (re-find test-pattern test-name))
               tests))))

(defn resolve-test-id
  [id-or-test-match]
  ;; if it's a number it must be a test id
  (if (number? (read-string id-or-test-match))
    (read-string id-or-test-match)
    ;; look up the test id by matching the test name
    (-> (resolve-tests id-or-test-match)
        keys
        first)))

(defn raw-performance
  [test-id]
  (fetch "performance/raw" {:query-params {:tests [test-id]}}))

(defn raw-performance-for-matched-test
  [id-or-test-match]
  (if-let [test-id (resolve-test-id id-or-test-match)]
    (let [perf (raw-performance test-id)]
      perf)
    (<< "Couldn't find a test matching ~{id-or-test-match}")))

(defn metric-by-index-or-pattern
  [metric-index-or-pattern metrics]
  (let [mni (if (number? metric-index-or-pattern)
              metric-index-or-pattern
              (read-string metric-index-or-pattern))]
    (if (number? mni)
      ;; look up by provided index
      (nth metrics mni)
      ;; look up by regex matching
      (let [pattern (re-pattern (<< "(?i)~{metric-index-or-pattern}"))]
        (first
          (filter
            (fn [{metric-name :name}] (re-find pattern metric-name))
            metrics))))))

(comment

  (def perf
    (raw-performance-for-matched-test "item"))

  ;; this drills into the chart legend, essentially
  (->> perf
       :body
       :detail
       :fields
       :synthetic_metrics
       (map :name))

  ;; actual perf data by ISP
  (let [items (-> perf
                  :body
                  :detail
                  :items)]
    (map (fn [{{breakdown-name :name} :breakdown_2
               metrics :synthetic_metrics}]
            ;; Webpage response
           [breakdown-name (nth metrics 17) ])
         items))


  (resolve-test-id "item page")

  ;; get details of a random test
  (->
    (tests)
    :result/value
    keys
    rand-nth
    test-by-id
    :body
    format-test-data)

  (fetch "nodes")

  (fetch "tests")
  (:body (fetch "tests"))

  (:body (fetch "performance"))

  (->>
    (fetch "tests")
    :body
    :items
    (map (juxt :id :name))
    sort)

  )
