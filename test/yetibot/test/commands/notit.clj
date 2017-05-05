(ns yetibot.test.commands.notit
  (:require
    [midje.sweet :refer [fact =>]]
    [yetibot.commands.notit :as notit]))

(def user {:name "TestBot"})

; !order reset
(fact reset-its
  (notit/reset-its nil)
  @notit/not-its => #{})
