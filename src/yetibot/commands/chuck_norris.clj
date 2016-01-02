(ns yetibot.commands.chuck-norris
  (:require
    [yetibot.core.util.http :refer [html-decode]]
    [yetibot.core.hooks :refer [cmd-hook cmd-unhook]]
    [yetibot.core.util.http :refer [get-json]]))

(def endpoint "http://api.icndb.com/jokes/random?limitTo=[nerdy]")

(defn chuck-joke
  {:doc "chuck # tell a random Chuck Norris joke"
   :yb/cat #{:fun}}
  [_] (-> (get-json endpoint) :value :joke html-decode))

(cmd-hook ["chuck" #"^chuck(norris)*$"]
          _ chuck-joke)
