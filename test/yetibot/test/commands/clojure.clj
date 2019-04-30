(ns yetibot.test.commands.clojure
  (:require
   [clojure.edn :as edn]
   [yetibot.core.midje :refer [value data]]
   [clojure.java.io :as io]
   [midje.sweet :refer [fact => anything throws]]
   [yetibot.commands.clojure :refer :all]))

(def sample-data
  (-> (io/resource "clj.edn") slurp edn/read-string))

(fact "should eval as clojure code and return response using clojail"
  (clojure-cmd {:args "(* 10 2)"}) => (value "20"))

(fact "should return error message when request fail"
  (clojure-cmd {:args "(+ 1 B)"}) => (throws Exception))

(fact "should allow access to data"
  (clojure-cmd {:args "(:location data)"
                :data {:location "Montana"}}) => (data "Montana")

  (clojure-cmd {:args "(:members data)"
                :data sample-data})

  )



