(ns yetibot.commands.stock
  (:require
   [clojure.walk :refer [postwalk]]
   [clojure.string :as string]
   [cuerdas.core :as cuerdas]
   [clojure.spec.alpha :as s]
   [clj-http.client :as client]
   [taoensso.timbre :refer [warn info]]
   [yetibot.core.config :refer [get-config]]
   [yetibot.core.hooks :refer [cmd-hook]]
   [yetibot.core.util.http :refer [get-json]]))

(s/def ::key string?)

(s/def ::config (s/keys :req-un [::key]))

(defn config [] (get-config ::config [:alphavantage]))

(defn build-query-params [q]
  (merge {:apikey (-> (config) :value :key)} q))

(def endpoint "https://www.alphavantage.co/query")

(defn transform-map-keys
  "Similar to clojure.walk/keywordize-keys but takes a transformation function
   instead of hardcoding `keyword`"
  [m transformer]
  (let [f (fn [[k v]] [(transformer k) v])]
    ;; only apply to maps
    (postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn key-transformer
  "Transform Alpha Vantage's weirdo keys"
  [k]
  (-> k
      name
      (string/replace-first #"^[\d\.\s]+" "")
      cuerdas/keyword))

(defn fetch [query]
  (let [{:keys [body] :as result}
        (client/get endpoint
                    {:as :json
                     :query-params (build-query-params query)})]
    (assoc result
           :body (transform-map-keys body key-transformer))))


(comment

  (fetch {:function "GLOBAL_QUOTE"
          :symbol "ebay"})
  (fetch {:function "GLOBAL_QUOTE"
          :symbol "lolnope"})
  ;; search
  (fetch {:function "SYMBOL_SEARCH"
          :keywords "lolnope"})
  (fetch {:function "SYMBOL_SEARCH"
          :keywords "baa"}))

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
    ;; note is used to return error messages from the API
    (let [{{note :note stock-info :global-quote} :body}
          (fetch {:function "GLOBAL_QUOTE"
                  :symbol (string/trim stock-symbol)})]
      (if (empty? stock-info)
        {:result/error
         (or note
             (str "Unable to find a stock for `" stock-symbol "` 🧐")) }
        {:result/data stock-info
         :result/value
         (map (fn [[label lookup-fn]]
                (info lookup-fn)
                (format "%s: %s"
                        label
                        (lookup-fn stock-info)))
              [["Symbol" :symbol]
               ["Latest Price" :price]
               ["High" :high]
               ["Low" :low]
               ["Change Percent" :change-percent]
               ["Latest trading day" :latest-trading-day]])}))
    (catch Exception _
      {:result/error
       (str "An error occurred trying to find stock " stock-symbol " 🧐")})))

(comment
  (get-price "ebay"))


(defn stock-cmd
  "stock <symbol> # displays current value in market"
  {:yb/cat #{:info}}
  [{:keys [args]}]
  (get-price args))

(defn search-cmd
  "stock search <query> # find the best matching symbols for <query>"
  [{[_ query] :match}]
  (let [{{matches :best-matches} :body} (fetch {:function "SYMBOL_SEARCH"
                                                :keywords query})]
    {:result/value
     (map
      (fn [{match-name :name
            match-symbol :symbol
            :keys [region
                   currency
                   timezone
                   market-open
                   market-close]}]
        (format "%s: %s - %s %s (%s-%s %s)"
                match-symbol match-name
                region currency market-open market-close
                timezone))
      matches)
     :result/data matches}))

(cmd-hook #"stock"
  #"search\s+(.+)" search-cmd
  _ stock-cmd)
