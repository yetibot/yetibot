(ns yetibot.commands.wolfram
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.util.http :refer [encode]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.data.json :as json]
    [clj-http.client :as client]
    [clojure.string :as string]))

(s/def ::appid string?)

(s/def ::config (s/keys :req-un [::appid]))

(def config (:value (get-config ::config [:wolfram])))
(def app-id (:appid config))
(def endpoint "http://api.wolframalpha.com/v2/query")

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
       :subpods
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
  (let [data (get-in (client/get endpoint
                                       {:query-params {:input q
                                                       :output "json"
                                                       :appid app-id
                                                       :reinterpret true}
                                        :as :json})
                     [:body :queryresult])]
    (if (:success data)
      {:result/value (map data-string (:pods data))
       :result/collection-path [:pods]
       :result/data data}
      (if (:error data)
        {:result/error (:error data)}
        {:result/value (catch-suggestion data)
         :result/data data}))))

(cmd-hook #"wolfram"
  #".*" search-wolfram)
