(ns yetibot.commands.catfacts
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [get-json]]))

(def endpoint "http://catfacts-api.appspot.com/api/facts")

(defn catfact
  "catfact # fetch a random cat fact"
  {:yb/cat #{:fun}}
  [_] (let [res (get-json endpoint)]
        (first (:facts res))))

(cmd-hook #"catfact"
          _ catfact)
