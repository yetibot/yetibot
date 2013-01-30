(ns yetibot.test.commands.notit
  (:require [yetibot.commands.notit :as notit])
  (:use [clojure.test]
        [yetibot.core]))

(def user {:name "TestBot"})

; !order reset
(deftest reset-its
         (is
           (= (notit/reset-its nil) #{})
           "it should reset the its list"))
