(ns yetibot.api.google
  (:require
   [taoensso.timbre :refer [info warn error]]
   [clojure.string :as s]
   [clojure.data.json :as json]
   [clj-http.client :as client]
   [yetibot.core.config :refer [conf-valid? config-for-ns]]))

(def config (config-for-ns))
(defn configured? [] (conf-valid? config))

(defonce api-url "https://www.googleapis.com/customsearch/v1?parameters")

(defonce keywords (atom {}))

;; validation and documentation
;; notes : sort needs to be improved upon
(defonce accepted-keywords
  {:q #".*", :c2coff #"0|1", :imgColorType #"mono|gray|color",
   :g1 #"[a-z]{2}", :googlehost #"google\.(com|[a-z]{2})"
   :imgDominantColor #"yellow|green|teal|blue|purple|pink|white|gray|black|brown",
   :imgSize #"clipart|face|lineart|news|photo", :filter #"0|1"
   :num #"\d|10", :safe #"high|medium|off", :searchType "image"
   :sort #".*", :start #"\d{1,3}", :siteSearch #".*", :siteSearchFilter #"e|i",
   :h1 #"[a-z]{2}|zh-Han[s|t]", :hq #".*",
   })

;; format a single query result
(defn format-result
  [result]
  (s/join
   "\n"
   (vals (select-keys result [:title :link :snippet]))))

;; map a vector of results to a vector
;; of string representations of the results
(defn format-results
  [result]
  (if (= (count (result :items)) 0)
    "Google returned no results"
    (let [result_ (result :items)
          indexed (map vector (range 10) result_)
          format  #(str (first %)
                        ". "
                        (format-result (second %))
                        "\n\n")]
        (map format indexed))))

(defn vanilla-search [q]
  (let [options {:query-params
                 {:q q
                  :key (config :api-key)
                  :cx (config :custom-search-engine-id)}}]
    (-> (client/get api-url options)
        (get :body)
        (json/read-json)
        (format-results))))
