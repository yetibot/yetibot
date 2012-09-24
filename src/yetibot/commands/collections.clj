(ns yetibot.commands.collections
  (:require
    [clojure.string :as s])
  (:use [yetibot.util :only (cmd-hook)]))

(defn ensure-items-collection [items]
  (if (coll? items)
    items
    (s/split items #"\n")))

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

; xargs
; example usage: !meme trending | xargs meme interesting:

(defn xargs
  "xargs <cmd> <list> # run <cmd> for every item in <list>; behavior is similar to xargs(1)'s xargs -n1"
  [cmd items user]
  (println "cmd is" cmd ". items are " items)
  (let [is (ensure-items-collection items)]
    (map #(yetibot.core/parse-and-handle-command (str cmd " " %) user nil) is)))

(cmd-hook #"xargs"
          _ (xargs opts args user))


