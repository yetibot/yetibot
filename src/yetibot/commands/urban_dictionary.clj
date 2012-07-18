(ns yetibot.commands.urban-dictionary
  (:require [clojure.string :as s]
            [http.async.client :as client]
            [clojure.data.json :as json])
  (:use [yetibot.util]))

(def endpoint "http://api.urbandictionary.com/v0/define?term=")

(defn search-urban-dictionary
  [q]
  (let [uri (str endpoint q)]
    (println (str "hitting" uri))
    (with-open [client (client/create-client)]
      (let [response (client/GET client uri)]
        (client/await response)
        (json/read-json (client/string response))))))

(defn search-cmd
  "urban <query> # search for <query> on Urban Dictionary"
  [q]
  (first
    (for [i (:list (search-urban-dictionary q))] (:definition i))))

(cmd-hook #"urban"
          #".*" (search-cmd p))
