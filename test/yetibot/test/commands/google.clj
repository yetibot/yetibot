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

(deftest options-processor-invalid-input
  (let [results (command/options-processor "jughead --nu 2")]
    (is (not (empty? (:errors results))))))

(deftest options-processor-valid-input
  (let [results (command/options-processor "jughead jones --num 2")]
    (is (and
         (empty? (:errors results))
         (= "jughead jones" (:query results))))))

(deftest search-function-input-validation-invalid-input
  (let [results (command/search-function-input-validation
                 "jughead --nu 2")]
    (is (= :failure (:status results)))))

(deftest search-function-input-validation-empty-query
  (let [results (command/search-function-input-validation "--num 2")]
    (is (= :failure (:status results)))))

(deftest keyword->apikeyword-test
  (let [test-data (map (fn [[k v]] [k (:keyword v)])
                       api/accepted-keywords)]
    (is (every? true?
                (map (fn [[k v]]
                       (= (command/keyword->apikeyword k) v))
                     test-data)))))

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

(deftest command-search-test-without-options
  (with-redefs-fn
    {#'api/search (fn [_ & _] test-call-response)}
    #(is (= (command/search {:match ["" "something"]})
            (api/format-results (:items test-call-response))))))

(deftest command-image-search-test-without-options
  (with-redefs-fn
    {#'api/image-search (fn [_ & _] test-call-response)}
    #(is (= (command/image-search {:match ["" "something"]})
            (api/format-results (:items test-call-response) :order :image)))))

(deftest command-search-test-with-valid-options
  (with-redefs-fn
    {#'api/search (fn [_ & _] test-call-response)}
    #(is (= (command/search {:match ["" "something --num 2 -y 3"]})
            (api/format-results (:items test-call-response))))))

(deftest command-image-search-test-with-valid-options
  (with-redefs-fn
    {#'api/image-search (fn [_ & _] test-call-response)}
    #(is (= (command/image-search {:match ["" "something -t photo -O nothing"]})
            (api/format-results (:items test-call-response) :order :image)))))

(deftest command-search-test-with-empty-query
  (with-redefs-fn
    {#'api/search (fn [_ & _] test-call-response)}
    #(is (= (command/search {:match ["" " --num 2 -y 3"]})
            (get command/messages :empty-query)))))

(deftest command-image-search-test-with-empty-query
  (with-redefs-fn
    {#'api/image-search (fn [_ & _] test-call-response)}
    #(is (= (command/image-search {:match ["" " -t photo -O nothing"]})
            (get command/messages :empty-query)))))

(deftest state-of-set-options-test
  (with-redefs-fn
    {#'command/options-atom (atom {:wilfred "demigod"})}
    #(is (not (empty? (command/state-of-set-options))))))

(deftest empty-state-of-set-options-test
  (is (empty? (command/state-of-set-options))))

(deftest populating-atom-from-options-in-config
  (let [key :imgcolortype
        val "mono"
        keyword (get-in api/accepted-keywords [key :keyword])]
    (with-redefs-fn
      {#'command/options-atom (atom {})
       #'api/populate-options-from-config (fn [] {key val})}
      #(is (= (do (command/load-options-from-file-into-atom)
                  @command/options-atom) {keyword val})))))
