(ns yetibot.commands.numbers
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [fetch]]))

(defn endpoint [number]
  (format "http://numbersapi.com/%s/math" number))

(def random-endpoint "http://numbersapi.com/random")

(defn random-number
  "number # lookup trivia on a random number"
  {:yb/cat #{:info}}
  [_] (fetch random-endpoint))

(defn number
  "number <n> # lookup mathematical trivia about <n>"
  {:yb/cat #{:info}}
  [{[_ n] :match}] (fetch (endpoint n)))

(cmd-hook #"number"
          #"(\d+)" number
          _ random-number)
