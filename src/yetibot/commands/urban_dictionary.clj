(ns yetibot.commands.urban-dictionary
  (:require [clojure.string :as s]
            [http.async.client :as client]
            [clojure.data.json :as json])
  (:use [yetibot.util]))

(def endpoint "http://api.urbandictionary.com/v0/")

(defn search-urban-dictionary
  [q]
  (let [uri (str endpoint "define?term=" q)]
    (with-open [client (client/create-client)]
      (let [response (client/GET client uri)]
        (client/await response)
        (json/read-json (client/string response))))))

(defn fetch-random []
  (let [uri (str endpoint "random")]
    (with-open [client (client/create-client)]
      (let [response (client/GET client uri)]
        (client/await response)
        (json/read-json (client/string response))))))


(defn random-cmd
  "urban random # fetch a random definition from Urban Dictionary"
  []
  (first
    (for [i (:list (fetch-random))] [(:word i) (:definition i)])))

(defn search-cmd
  "urban <query> # search for <query> on Urban Dictionary"
  [q]
  (first
    (for [i (:list (search-urban-dictionary q))] (:definition i))))

(cmd-hook #"urban"
          #"^random" (random-cmd)
          #".*" (search-cmd p))
