(ns yetibot.commands.random
  (:require
    [clojure.contrib.string :as s])
  (:use [yetibot.util :only (cmd-hook)]))

(defn random
  "random <list> # returns a random item where <list> is a comma-separated list of items.
  Can also be used to extract a random item when a collection is piped to random."
  [items]
  (rand-nth
    (if (coll? items)
      items
      (s/split #"," items))))

(cmd-hook #"random"
          _ (random p))
