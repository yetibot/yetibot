(ns yetibot.test.api.cloudflare-pagination
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.test.integration.cloudflare-models :as fixture])
  (:import [java.io ByteArrayInputStream]))

(def records
  (mapv #(assoc (first fixture/records)
                :name (str "@cf/fixture/model-" %)) (range 73)))

(defn catalog-run
  ([change] (catalog-run change #(if (= % 1) records [])))
  ([change batch-for]
   (let [pages (atom []) posts (atom 0)
         models
         (with-redefs
           [http/post (fn [& _] (swap! posts inc)
                        (throw (AssertionError. "No inference")))
            http/get
            (fn [_ {:keys [query-params]}]
              (let [page (:page query-params)
                    batch (batch-for page)
                    info (change page {:page page :per_page 100
                                       :count (count batch) :total_count 327})]
                (swap! pages conj page)
                {:status 200
                 :body (ByteArrayInputStream.
                         (.getBytes
                           (json/generate-string
                             {:success true :result batch :result_info info})
                           "UTF-8"))}))]
           (ai/models fixture/provider))]
     {:pages @pages :models models :posts @posts})))

(fact "filtered counts traverse all advertised pages before becoming complete"
  (let [{:keys [pages models posts]} (catalog-run (fn [_ info] info))]
    pages => [1 2 3 4]
    (count models) => 73
    (:complete? (meta models)) => true
    posts => 0))

(fact "an empty intermediate page cannot hide a later advertised model"
  (let [{:keys [pages models]}
        (catalog-run (fn [_ info] info)
                     #(case % 1 records 3 [(first fixture/records)] []))]
    pages => [1 2 3 4]
    (count models) => 74
    (:complete? (meta models)) => true))

(facts "inconsistent page metadata never establishes routing readiness"
  (doseq [change [(fn [page info]
                   (cond-> info (= page 2) (assoc :page 1)))
                 (fn [page info]
                   (cond-> info (= page 2) (assoc :total_count 328)))
                 (fn [_ info] (assoc info :total_count 20))]]
    (let [{:keys [models posts]} (catalog-run change)]
      (fact (:complete? (meta models)) => false
            posts => 0))))
