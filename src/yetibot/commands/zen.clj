(ns yetibot.commands.zen
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [fetch]]))

(def endpoint "https://api.github.com/zen")

(defn zen
  "zen # fetch zen wisdom"
  [_] (fetch endpoint))

(cmd-hook #"zen"
          _ zen)
