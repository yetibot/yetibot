(ns yetibot.test.api.google
  (:require
   [midje.sweet :refer [fact => anything =throws=>]]
   [clj-http.client :as client]
   [clojure.data.json :as json]
   [yetibot.api.google :as api]))

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

(fact format-result-normal-order-test
  (let [test-data (nth test-items 0)]
    (api/format-result test-data) => (str (:title test-data)
                                          "\n" (:link test-data)
                                          "\n" (:snippet test-data))))

(fact format-result-image-order-test
  (let [test-data (nth test-items 0)]
    (api/format-result test-data :order :image) => (str (:title test-data)
                                                        "\n" (:snippet test-data)
                                                        "\n" (:link test-data))))

(fact format-results-normal-order-test
  (api/format-results test-items) => [(str "1. " (api/format-result (nth test-items 0)) "\n\n")
                                      (str "2. " (api/format-result (nth test-items 1)) "\n\n")])

(fact format-results-image-order-test
  (api/format-results test-items :order :image) => [(str "1. " (api/format-result (nth test-items 0)
                                                                                  :order :image) "\n\n")
                                                    (str "2. " (api/format-result (nth test-items 1)
                                                                                  :order :image) "\n\n")])

(fact search-test
  (api/search "query") => test-call-response
  (provided
    (client/get anything anything) => test-call-response-json))

(fact search-test-with-bad-http-return
  (api/search "query") => nil?
  (provided
    (client/get anything anything) =throws=> (Exception. "Bad return")))
