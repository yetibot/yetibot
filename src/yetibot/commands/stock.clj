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

(defn get-body
  "Gets json body from response"
  [json]
  (-> json
      :body
      (s/replace-first "//" "")
      json/parse-string))

(defn parse-quote
  "Parses most relevant information from json"
  [stock-symbol]
  (let [response (client/get (endpoint stock-symbol){:throw-exceptions false})]
    (if (= (:status response) 200)
      (let [quote (get-body response)]
        (->> quote
             first
             keywordize-keys
             ((juxt :name :l :hi :lo :mc :cp :ecp))
             (interleave ["Name:" "Last Price:" "High:" "Low:" "Market Cap:" "Change Percent:" "After Hours Change Percent:"])
             (partition 2)
             (map #(s/join " " %))))
      (str "Unable to find symbol for " stock-symbol ". Try another symbol such as MSFT or AAPL."))))

(defn stock-cmd
  "stock <symbol> # displays current value in market"
  {:yb/cat #{:info}}
  [{:keys [args]}]
  (parse-quote args))

(cmd-hook ["stock" #"^stock$"]
          _ stock-cmd)
