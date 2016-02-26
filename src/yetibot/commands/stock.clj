(ns yetibot.commands.stock
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clj-http.client :as client]
    [cheshire.core :as json]
    [clojure.walk :refer [keywordize-keys]]))

(defn endpoint
  "Creates a Google Finance query from stock symbol"
  [stock-symbol]
  (str "http://www.google.com/finance/info?infotype=infoquoteall&q=" stock-symbol))

(defn get-quote
  "Gets response from Google Finance and retrieves json"
  [stock-symbol]
  (let [resp (client/get (endpoint stock-symbol))]
    (-> resp
        :body
        (s/replace-first "//" "")
        json/parse-string)))

(defn parse-quote
  "Parses most relevant information from json"
  [stock-symbol]
  (let [quote (get-quote stock-symbol)]
    (->> quote
         first
         keywordize-keys
         ((juxt :name :l :hi :lo :mc :cp))
         (interleave ["Name:" "Last Price:" "High:" "Low:" "Market Cap:" "Change Percent:"])
         (partition 2)
         (map #(s/join " " %)))))


(defn stock-cmd
  "stock <symbol> # displays current value in market"
  {:yb/cat #{:info}}
  [{:keys [args]}]
  (parse-quote args))

(cmd-hook ["stock" #"^stock$"]
          _ stock-cmd)
