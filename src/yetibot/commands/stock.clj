(ns yetibot.commands.stock
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [get-json]]))

(defn endpoint
  "Creates a YQL query from stock symbol"
  [stock-symbol]
  (str "http://dev.markitondemand.com/MODApis/Api/v2/Quote/json?symbol="
       stock-symbol))

(defn format-percent
  "Formats number in map as percent"
  [k m]
  (update-in m [k] #(format "%.2f%%" (double %))))

(defn get-price
  "Gets the price from a stock symbol via Yahoo API"
  [stock-symbol]
  (let [stock-info (get-json (endpoint stock-symbol))]
    (if (:Name stock-info)
      {:result/data stock-info
       :result/value
       (->> stock-info
            (format-percent :ChangePercent)
            ((juxt :Name :LastPrice :High :Low :MarketCap :ChangePercent))
            (interleave ["Name:" "Last Price:" "High:" "Low:" "Market Cap:"
                         "Change Percent:"])
            (partition 2)
            (map #(s/join " " %)))}
      (:Message stock-info))))

(defn stock-cmd
  "stock <symbol> # displays current value in market"
  {:yb/cat #{:info}}
  [{:keys [args]}]
  (get-price args))

(cmd-hook ["stock" #"^stock$"]
          _ stock-cmd)
