(ns yetibot.test.commands.clojure
  (:require
   [clojure.edn :as edn]
   [yetibot.core.midje :refer [value data]]
   [clojure.java.io :as io]
   [midje.sweet :refer [fact facts => anything throws]]
   [yetibot.commands.clojure :refer :all]))

(def sample-data
  {:id 1234,
   :members
   '({:id 1001, :tid 2001}
     {:id 1002, :tid 2002}
     {:id 1003, :tid 2003}
     {:id 1004, :tid 2004}
     {:id 1005, :tid 2005}
     {:id 1006, :tid 2006}
     {:id 3001, :tid 4001}
     {:id 3002, :tid 4002}
     {:id 3003, :tid 4003}
     {:id 3004, :tid 4004}
     {:id 3005, :tid 4005}
     {:id 3006, :tid 4006})})

(fact "should eval as clojure code and return response using clojail"
  (clojure-cmd {:args "(* 10 2)"}) => (value "20"))

(fact "should return error message when request fail"
  (clojure-cmd {:args "(+ 1 B)"}) => (throws Exception))

(fact "should allow access to data"
  (clojure-cmd {:args "(:location data)"
                :data {:location "Montana"}}) => (data "Montana"))

(fact "should work with nested seq data structures"
  (clojure-cmd {:args "(count (:members data))"
                :data sample-data}) => (value "12"))

(facts "should not be able to access outer Yetibot context"
  (clojure-cmd
    {:args "(clojail.testers/blanket \"foo\")"}) => (throws Exception)
  (clojure-cmd
    {:args "(yetibot.core.adapters.init/adapters-config)"}) => (throws Exception)
  (clojure-cmd {:args "(yetibot.api.github/config)"}) => (throws Exception))
