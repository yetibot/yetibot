(ns yetibot.commands.catfacts
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [get-json]]))

(def endpoint-ptrn "http://www.catfact.info/api/v1/facts.json?page=%d&per_page=1")

(defonce total-facts (atom nil))

(defn- fetch-catfact
  "Fetches the catfact with the given id (>0) and returns its json map"
  [id]
  (get-json (format endpoint-ptrn id)))

(defn- get-total-facts
  "Gets the total number of facts, if not known fetch the total and return it"
  []
  (or @total-facts (:total (fetch-catfact 0))))

(defn catfact
  "catfact # fetch a random cat fact"
  {:yb/cat #{:fun}}
  [_] (let [catmap (fetch-catfact (inc (rand-int (get-total-facts))))]
        (reset! total-facts (:total catmap))
        (:details (first (:facts catmap)))))

(cmd-hook #"catfact"
          _ catfact)
