(ns yetibot.commands.chuck-norris
  (:require [yetibot.hooks :refer [cmd-hook cmd-unhook]]
            [yetibot.util.http :refer [get-json]]))

(def endpoint "http://api.icndb.com/jokes/random")

(defn chuck-joke
  "chuck # tell a random Chuck Norris joke"
  [_] (-> (get-json endpoint) :value :joke))

(cmd-hook ["chuck" #"^chuck(norris)*$"]
          _ chuck-joke)
