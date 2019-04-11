(ns yetibot.test.commands.clojure
  (:require
   [yetibot.core.midje :refer [value data]]
   [midje.sweet :refer [fact => anything throws]]
   [yetibot.commands.clojure :refer :all]))

(fact "should eval as clojure code and return response using clojail"
  (clojure-cmd {:args "(* 10 2)"}) => (value "20"))

(fact "should return error message when request fail"
  (clojure-cmd {:args "(+ 1 B)"}) => (throws Exception))

(fact "should allow access to data"
  (clojure-cmd {:args "(:location data)"
                :data {:location "Montana"}}) => (data "Montana"))
