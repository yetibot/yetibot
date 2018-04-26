(ns yetibot.commands.catfacts
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [clj-http.client :as client]))

(def endpoint "https://catfact.ninja/fact")

(defn- fetch-catfact
  "Fetches a random catfact"
  []
  (client/get endpoint {:as :json}))

(defn catfact
  "catfact # fetch a random cat fact"
  {:yb/cat #{:fun}}
  [_]
  (-> (fetch-catfact) :body :fact))

(cmd-hook #"catfact"
  _ catfact)
