(ns yetibot.test.commands.google
  (:require
   [clojure.test :refer :all]
   [yetibot.commands.google :as command]
   [yetibot.api.google :as api]
   ))

(defonce test-call-response
  {:items [{:title "x-files"
            :link  "httbs://yyy.x-files.com"
            :snippet "Best paranormal series staring Davi..."},
           {:title "wilfred"
            :link "httbs://yyy.wilfred-smashing-ryan.com"
            :snippet "The sun is an attention grabbing whore.."}]
   :searchInformation {:totalResults "2"}})

(defonce no-results-response {:searchInformation {:totalResults "0"}})

(deftest command-search-no-results-test
  (with-redefs-fn
    {#'api/search (fn [_ & _] no-results-response)}
    #(is (= (command/search {:match ["" "something"]})
            (:search command/messages)))))

(deftest command-image-search-no-results-test
  (with-redefs-fn
    {#'api/image-search (fn [_ & _] no-results-response)}
    #(is (= (command/image-search {:match ["" "something"]})
            (:image command/messages)))))

(deftest command-search-bad-response-test
  (with-redefs-fn
    {#'api/search (fn [_ & _] nil)}
    #(is (= (command/search {:match ["" "something"]})
            (:google-died command/messages)))))

(deftest command-image-search-bad-response-test
  (with-redefs-fn
    {#'api/image-search (fn [_ & _] nil)}
    #(is (= (command/image-search {:match ["" "something"]})
            (:google-died command/messages)))))

(deftest command-search-test
  (with-redefs-fn
    {#'api/search (fn [_ & _] test-call-response)}
    #(is (= (command/search {:match ["" "something"]})
            (api/format-results (:items test-call-response))))))

(deftest command-image-search-test
  (with-redefs-fn
    {#'api/image-search (fn [_ & _] test-call-response)}
    #(is (= (command/image-search {:match ["" "something"]})
            (api/format-results (:items test-call-response) :order :image)))))
