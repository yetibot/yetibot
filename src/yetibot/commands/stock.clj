(ns yetibot.commands.stock
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [get-json]]))

(defn endpoint
  "Returns an API endpoint to query a stock symbol"
  [stock-symbol]
  (format "https://api.iextrading.com/1.0/stock/%s/quote?displayPercent=true"
          stock-symbol))

(defn format-percent
  "Formats number in map as percent"
  [percentage]
  (format "%.4f%%" (double percentage)))

(defn format-number
  "Formats a number with commas"
  [number]
  (format "%,d" number))

(defn get-price
  "Gets the price from a stock symbol via Yahoo API"
  [stock-symbol]
  (try
    (let [stock-info (get-json (endpoint stock-symbol))]
      {:result/data stock-info
       :result/value
       (map (fn [[label lookup-fn]]
              (format "%s: %s"
                      label
                      (lookup-fn stock-info)))
            [["Name" :companyName]
             ["Latest Price" :latestPrice]
             ["High" :high]
             ["Low" :low]
             ["Market Cap" (comp format-number :marketCap)]
             ["Change Percent" (comp format-percent :changePercent)]
             ["Week 52 High" :week52High]
             ["Week 52 Low" :week52Low]
             ["Latest time" :latestTime]])})
    (catch Exception _
      (str "An error occurred trying to find stock " stock-symbol " 🧐"))))

(comment
  ;; Example response from EIX API
  {:iexMarketPercent 0.05299,
   :week52High 39.275,
   :open 36.72,
   :week52Low 28.14,
   :latestTime "3:28:44 PM",
   :iexLastUpdated 1512678524621,
   :latestSource "IEX real time price",
   :avgTotalVolume 9247166,
   :latestPrice 36.86,
   :iexAskSize 100,
   :symbol "EBAY",
   :primaryExchange "Nasdaq Global Select",
   :latestVolume 5006246,
   :sector "Consumer Cyclical",
   :openTime 1512657000686,
   :marketCap 38502892257,
   :close 36.83,
   :high 37.15,
   :iexBidSize 100,
   :closeTime 1512594000441,
   :delayedPrice 36.78,
   :iexBidPrice 36.84,
   :changePercent 8.1E-4,
   :previousClose 36.83,
   :iexRealtimePrice 36.86,
   :low 36.5,
   :iexAskPrice 37.81,
   :change 0.03,
   :companyName "eBay Inc.",
   :iexRealtimeSize 100,
   :ytdChange 0.2342493297587131,
   :delayedPriceTime 1512677641600,
   :latestUpdate 1512678524621,
   :peRatio 23.04,
   :calculationPrice "tops",
   :iexVolume 265281})

(defn stock-cmd
  "stock <symbol> # displays current value in market"
  {:yb/cat #{:info}}
  [{:keys [args]}]
  (get-price args))

(cmd-hook ["stock" #"^stock$"]
          _ stock-cmd)
