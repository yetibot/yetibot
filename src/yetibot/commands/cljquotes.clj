(ns yetibot.commands.cljquotes
  (:require
    [clojure.core.memoize :as memo]
    [clojure.edn :as edn]
    [clojure.string :as string]

    [clj-http.client :as client]

    [yetibot.core.hooks :refer [cmd-hook]]
    ))

(defn fetch-quotes
  []
  (let [url "https://raw.githubusercontent.com/Azel4231/clojure-quotes/master/quotes.edn"]
    (-> (client/get url)
        :body
        edn/read-string)))

;; only fetch once per day
(def quote-fetch-delay (* 24 60 60 1000))

(def quotes
  (memo/ttl fetch-quotes :ttl/threshold quote-fetch-delay))

(defn format-quote
  [quote]
  (let [{:clojure-quotes.core/keys [text quotee reference]} quote
        {:clojure-quotes.core/keys [url time]} reference]
    (str ">>> " text "\n"
         "-- " quotee
         (when url (str " (" url (when time (str " @" time)) ")")))))

(defn quote-cmd
  "cljquote # random clojure quote"
  [context]
  (let [quote (rand-nth (quotes))]
    {:result/value (format-quote quote)
     :result/data quote}))

(cmd-hook #"cljquote"
  _ quote-cmd)

#_(format-quote (rand-nth (quotes)))
#_(string/join "\n\n" (map format-quote (quotes)))
