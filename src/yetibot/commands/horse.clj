(ns yetibot.commands.horse
  (:require
    [clojure.string :refer [trim]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [fetch]]))

(def endpoint "http://horsefortun.es/get")

(defn horse
  "horse # fetch wisdom from horse_ebooks"
  [_] (-> endpoint fetch trim))

(cmd-hook ["horse" #"^horse$"]
          _ horse)
