(ns yetibot.test.commands.notit
  (:require
    [yetibot.commands.notit :as notit]
    [clojure.test :refer :all]))

(def user {:name "TestBot"})

; !order reset
(deftest reset-its
  (notit/reset-its nil)
  (is (= @notit/not-its #{})
      "it should reset the its list"))
