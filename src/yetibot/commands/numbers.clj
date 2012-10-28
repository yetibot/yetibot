(ns yetibot.commands.numbers
  (:use [yetibot.util :only [cmd-hook]]
        [yetibot.util.http :only [fetch]]))

(defn endpoint [number]
  (format "http://numbersapi.com/%s/math" number))

(def random-endpoint "http://numbersapi.com/random")

(defn random-number
  "number # lookup trivia on a random number"
  [] (fetch random-endpoint))

(defn number
  "number <n> # lookup mathematical trivia about <n>"
  [n] (fetch (endpoint n)))

(cmd-hook #"number"
          #"(\d+)" (number (nth p 1))
          #"^$" (random-number))
