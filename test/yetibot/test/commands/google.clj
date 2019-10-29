(ns yetibot.test.commands.google
  (:require
   [midje.sweet :refer [fact => truthy anything has]]
   [yetibot.commands.google :as command]
   [yetibot.api.google :as api]))

(defonce test-call-response
  {:items [{:title "x-files"
            :link  "httbs://yyy.x-files.com"
            :snippet "Best paranormal series staring Davi..."},
           {:title "wilfred"
            :link "httbs://yyy.wilfred-smashing-ryan.com"
            :snippet "The sun is an attention grabbing whore.."}]
   :searchInformation {:totalResults "2"}})

(defonce no-results-response {:searchInformation {:totalResults "0"}})

(fact options-processor-invalid-input
  (let [results (command/options-processor "jughead --nu 2")]
    (seq (:errors results))) => truthy)

(fact options-processor-valid-input
  (let [results (command/options-processor "jughead jones --num 2")]
      (empty? (:errors results)) => true
      (:query results) => "jughead jones"))

(fact search-function-input-validation-invalid-input
  (let [results (command/search-function-input-validation
                 "jughead --nu 2")]
    (:status results) => :failure))

(fact search-function-input-validation-empty-query
  (let [results (command/search-function-input-validation "--num 2")]
    (:status results) => :failure))

(fact keyword->apikeyword-test
  (let [test-data (map (fn [[k v]] [k (:keyword v)])
                       api/accepted-keywords)]
      (map (fn [[k v]] (= (command/keyword->apikeyword k) v)) test-data)) => (has every? true?))

(fact command-search-no-results-test
  (command/search {:match ["" "something"]}) => (:search command/messages)
  (provided
    (api/search anything :args anything) => no-results-response))

(fact command-image-search-no-results-test
  (command/image-search {:match ["" "something"]}) => (:image command/messages)
  (provided
    (api/image-search anything :args anything) => no-results-response))

(fact command-search-bad-response-test
  (command/search {:match ["" "something"]}) => (:google-died command/messages)
  (provided
    (api/search anything :args anything) => nil))

(fact command-image-search-bad-response-test
  (command/image-search {:match ["" "something"]}) => (:google-died command/messages)
  (provided
    (api/image-search anything :args anything) => nil))

(fact command-search-test-without-options
  (command/search {:match ["" "something"]}) => (api/format-results (:items test-call-response))
  (provided
    (api/search anything :args anything) => test-call-response))

(fact command-image-search-test-without-options
  (command/image-search {:match ["" "something"]}) =>
    (api/format-results (:items test-call-response) :order :image)
  (provided
    (api/image-search anything :args anything) => test-call-response))

(fact command-search-test-with-valid-options
  (command/search {:match ["" "something --num 2 -y 3"]}) =>
    (api/format-results (:items test-call-response))
  (provided
    (api/search anything :args anything) => test-call-response))

(fact command-image-search-test-with-valid-options
  (command/image-search {:match ["" "something -t photo -O nothing"]}) =>
    (api/format-results (:items test-call-response) :order :image)
  (provided
    (api/image-search anything :args anything) => test-call-response))

(fact command-search-test-with-empty-query
  (command/search {:match ["" " --num 2 -y 3"]}) =>
    (get command/messages :empty-query))

(fact command-image-search-test-with-empty-query
  (command/image-search {:match ["" " -t photo -O nothing"]}) =>
    (get command/messages :empty-query))

(fact state-of-set-options-test
  (with-redefs {#'command/options-atom (atom {:wilfred "demigod"})}
    #(seq (command/state-of-set-options)) => truthy))

;; if google is configured this will not be empty?
;; (fact empty-state-of-set-options-test
;;   (command/state-of-set-options) => empty?)

(fact populating-atom-from-options-in-config
  (let [key :imgcolortype
        val "mono"
        keyword (get-in api/accepted-keywords [key :keyword])]
    (with-redefs-fn
      {#'command/options-atom (atom {})
       #'api/populate-options-from-config (fn [] {key val})}
      #(clojure.test/is (= (do (command/load-options-from-file-into-atom)
                  @command/options-atom) {keyword val})))))
