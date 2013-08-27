(ns yetibot.commands.jargon
  (:require
    [yetibot.hooks :refer [cmd-hook]]
    [yetibot.models.jargon :refer [jargon]]))

(defn list-cmd
  "jargon list # list known jargon"
  [_] jargon)

(defn rand-jargon
  "jargon # respond with random jargon"
  [_] (rand-nth jargon))

(cmd-hook #"jargon"
          #"list" list-cmd
          _ rand-jargon)
