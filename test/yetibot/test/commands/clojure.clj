(ns yetibot.test.commands.clojure
  (:require
    [midje.sweet :refer [fact => anything throws]]
    [yetibot.commands.clojure :refer :all]
    ))

(fact "should eval as clojure code and return response using clojail"
  (clojure-cmd {:args "(* 10 2)"}) => "20")

(fact "should return error message when request fail"
  (clojure-cmd {:args "(+ 1 B)"}) => (throws Exception))
