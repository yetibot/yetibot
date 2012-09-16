(ns yetibot.commands.collections
  (:require
    [clojure.contrib.string :as s])
  (:use [yetibot.util :only (cmd-hook)]))

(defn ensure-items-collection [items]
  (if (coll? items)
    items
    (s/split #"," items)))

; random

(defn random
  "random <list> # returns a random item where <list> is a comma-separated list of items.
  Can also be used to extract a random item when a collection is piped to random."
  [items]
  (rand-nth (ensure-items-collection items)))

(cmd-hook #"random"
          _ (random p))

; head

(defn head
  "head <list> # returns the first item from the <list>"
  [items]
  (first (ensure-items-collection items)))

(cmd-hook #"head"
          _ (head p))

; tail

(defn tail
  "tail <list> # returns the last item from the <list>"
  [items]
  (last (ensure-items-collection items)))

(cmd-hook #"tail"
          _ (tail p))
