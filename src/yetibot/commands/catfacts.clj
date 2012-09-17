(ns yetibot.commands.catfacts
  (:use [yetibot.util :only [cmd-hook]]
        [yetibot.util.http :only [get-json]]))

(def endpoint "http://catfacts-api.appspot.com/api/facts")

(defn catfact
  "catfact # fetch a random cat fact"
  []
  (let [res (get-json endpoint)]
    (first (:facts res))))

(cmd-hook #"catfact"
          _ (catfact))
