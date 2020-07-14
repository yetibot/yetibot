(ns yetibot.commands.wolfram
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.util.http :refer [encode]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.data.json :as json]
    [taoensso.timbre :refer [error]]
    [clojure.string :as string]))

(s/def ::appid string?)

(s/def ::config (s/keys :req-un [::appid]))

(def config (:value (get-config ::config [:wolfram])))
(def app-id (:appid config))
(def endpoint (str "http://api.wolframalpha.com/v2/query?output=json&appid="
                   app-id))

(defn plaintext-or-image
  "Return the subpods plaintext if present, otherwise it's image link."
  [subpod]
  (let [plaintext (:plaintext subpod)]
    (if-not (empty? plaintext)
      plaintext
      (get-in subpod [:img :src]))))

(defn data-string
  "Concatenate pod title and all subpods' data."
  [pod]
  (->> pod
       (:subpods)
       (map plaintext-or-image)
       (string/join "; ")
       (str (:title pod) ": ")))

(defn catch-suggestion
  "Print some help if Wolfram can't interpret the query."
  [wolfram-data]
  (or (get-in wolfram-data [:tips :text])
      (if-let [suggestion (get-in wolfram-data [:didyoumeans :val])]
        (str "Did you mean " suggestion "?"))
      (get-in wolfram-data [:languagemsg :english])
      "Wolfram couldn't interpret your query."))

(defn search-wolfram
  "wolfram <query> # search for <query> on Wolfram Alpha"
  {:yb/cat #{:info :img}}
  [{q :match}]
  (let [data (:queryresult (json/read-str
                            (slurp (str endpoint "&input=" (encode q)))
                            :key-fn keyword))]
    (if (:success data)
      {:result/value (map data-string (:pods data))
       :result/collection-path [:pods]
       :result/data data}
      {:result/value (if (:error data)
                       (do (error data)
                           "Oops, something went wrong.")
                       (catch-suggestion data))
       :result/data data})))

(cmd-hook #"wolfram"
  #".*" search-wolfram)
