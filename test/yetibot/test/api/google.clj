(ns yetibot.test.api.google
  (:require
   [clojure.test :refer :all]
   [clj-http.client :as client]
   [clojure.data.json :as json]
   [yetibot.api.google :as api]
   ))

(defonce test-call-response
   {:items [{:title "x-files"
            :link  "httbs://yyy.x-files.com"
            :snippet "Best paranormal series staring Davi..."
            :image "httbs://yyy.hot-picture-of-danna-scully.com"},
           {:title "wilfred"
            :link "httbs://yyy.wilfred-smashing-ryan.com"
            :snippet "The sun is an attention grabbing whore.."
            :image "httbs://yyy.wilfred-smoking-bong.com"}]})

(defonce test-items (:items test-call-response))
(defonce test-call-response-json
  {:body (json/write-str test-call-response)})

(deftest empty-config-configured?-test
  (with-redefs-fn
    {#'api/config {}}
    #(is (= (api/configured?)
            false))))

(deftest config-with-one-key-configured?-test
  "should return false, as both
   keys are required for the configuration
   for the search engine"
  (with-redefs-fn
    {#'api/config {:api-key "something"}}
    #(is (= (api/configured?)
            false))))

(deftest config-with-two-keys-configured?-test
  "should return true, as both
   keys (correct keys) are present"
  (with-redefs-fn
    {#'api/config {:api-key "something"
                   :custom-search-engine-id "something"}}
    #(is (= (api/configured?)
            true))))

(deftest format-result-normal-order-test
  (let [test-data (nth test-items 0)]
    (is (= (api/format-result test-data)
           (str (:title test-data)
                "\n" (:link test-data)
                "\n" (:snippet test-data))))))

(deftest format-result-image-order-test
  (let [test-data (nth test-items 0)]
    (is (= (api/format-result test-data :order :image)
           (str (:title test-data)
                "\n" (:snippet test-data)
                "\n" (:link test-data))))))

(deftest format-results-normal-order-test
  (is (= (api/format-results test-items)
         [(str "1. " (api/format-result (nth test-items 0)) "\n\n")
          (str "2. " (api/format-result (nth test-items 1)) "\n\n")])))

(deftest format-results-image-order-test
  (is (= (api/format-results test-items :order :image)
         [(str "1. " (api/format-result (nth test-items 0)
                                        :order :image) "\n\n")
          (str "2. " (api/format-result (nth test-items 1)
                                        :order :image) "\n\n")])))

(deftest search-test
  (with-redefs-fn
    {#'client/get (fn [_ & _] test-call-response-json)}
    #(is (= (api/search "query")
            test-call-response))))

(deftest search-test-with-bad-http-return
  (with-redefs-fn
    {#'client/get (fn [_ & _] (throw (Exception. "Bad return")))}
    #(is (nil? (api/search "query")))))
