(ns yetibot.commands.horse
  (:require
    [yetibot.hooks :refer [cmd-hook]]
    [yetibot.util.http :refer [fetch]]))

(def endpoint "http://horsefortun.es/get")

(defn horse
  "horse # fetch wisdom from horse_ebooks"
  [_] (fetch endpoint))

(cmd-hook #"horse"
          _ horse)
