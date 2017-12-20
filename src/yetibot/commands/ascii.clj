(ns yetibot.commands.ascii
  (:require 
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [fetch encode]]))

(def endpoint "http://artii.herokuapp.com/make?text=")

(defn ascii
  "ascii <text> # generates ascii art representation of <text>"
  [{text :match}]
  (fetch (str endpoint (encode text))))

(cmd-hook ["ascii" #"^ascii$"]
          #"^.+" ascii)
