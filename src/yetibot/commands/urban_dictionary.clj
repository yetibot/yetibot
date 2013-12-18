(ns yetibot.commands.urban-dictionary
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [encode get-json]]
    [clojure.data.json :as json]))

(def endpoint "http://api.urbandictionary.com/v0/")

(defn- call [querystring]
  (get-json (str endpoint querystring)))

(defn search-urban-dictionary
  [q] (call (format "define?term=%s" (encode q))))

(defn fetch-random [] (call "random"))

(defn format-def [result]
  (first (for [i (:list result)] [(:word i) (:definition i)])))

(defn random-cmd
  "urban random # fetch a random definition from Urban Dictionary"
  [_] (format-def (fetch-random)))

(defn search-cmd
  "urban <query> # search for <query> on Urban Dictionary"
  [{q :match}] (format-def (search-urban-dictionary q)))

(cmd-hook #"urban"
          #"^random" random-cmd
          #".*" search-cmd)
