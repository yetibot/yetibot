(ns yetibot.commands.zen
  (:use [yetibot.hooks :only [cmd-hook]]
        [yetibot.util.http :only [fetch]]))

(def endpoint "https://api.github.com/zen")

(defn zen
  "zen # fetch a random cat fact"
  [_] (fetch endpoint))

(cmd-hook #"zen"
          _ zen)
