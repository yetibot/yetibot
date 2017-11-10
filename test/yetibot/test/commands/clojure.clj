(ns yetibot.test.commands.clojure
  (:require
    [midje.sweet :refer [fact => anything]]
    [yetibot.commands.clojure :refer :all]
    [yetibot.core.util.http :refer [get-json map-to-query-string]]))

;; tryclj.com is expired :(
(comment
  (fact "should eval as clojure code and return response from real service"
    (clojure-cmd {:args "(* 10 2)"}) => "20")

  (fact "should eval as clojure code and return response"
    (clojure-cmd {:args "(+ 1 2)"}) => "3"
    (provided
      (get-json anything) => {:expr "(+ 1 2)" :result "3"}))

  (fact "should return error message when request fail"
    (clojure-cmd {:args "(+ 1 B)"}) => "Error"
    (provided
      (get-json anything) => {:error true :message "Error"}))

  )
