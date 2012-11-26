(ns yetibot.commands.numbers
  (:use [yetibot.hooks :only [cmd-hook]]
        [yetibot.util.http :only [fetch]]))

(defn endpoint [number]
  (format "http://numbersapi.com/%s/math" number))

(def random-endpoint "http://numbersapi.com/random")

(defn random-number
  "number # lookup trivia on a random number"
  [_] (fetch random-endpoint))

(defn number
  "number <n> # lookup mathematical trivia about <n>"
  [{[_ n] :match}] (fetch (endpoint n)))

(cmd-hook #"number"
          #"(\d+)" number
          _ random-number)
