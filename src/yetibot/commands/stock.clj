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
      (let [{:keys [name l hi lo mc cp el ecp]}(->> (get-body response) first keywordize-keys)]
        (remove
         nil?
           [(str "Name: " name)
            (str "Last Price: " l)
            (str "High: " hi)
            (str "Low: " lo)
            (str "Market Cap: " mc)
            (str "Change Percent: " cp"%")
            (when (seq el) (str "After Hours Price: " el))
            (when (seq ecp) (str "After Hours Change Percent: " ecp "%"))]))
      (str "Unable to find symbol for " stock-symbol ". Try another symbol such as MSFT or AAPL."))))

(defn stock-cmd
  "stock <symbol> # displays current value in market"
  {:yb/cat #{:info}}
  [{:keys [args]}]
  (parse-quote args))

(cmd-hook ["stock" #"^stock$"]
          _ stock-cmd)
