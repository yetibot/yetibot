(ns yetibot.commands.jargon
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.models.jargon :refer [jargon]]))

(defn list-cmd
  "jargon list # list known jargon"
  {:yb/cat #{:fun}}
  [_] jargon)

(defn rand-jargon
  "jargon # respond with random jargon"
  {:yb/cat #{:fun}}
  [_] (rand-nth jargon))

(cmd-hook #"jargon"
          #"list" list-cmd
          _ rand-jargon)
