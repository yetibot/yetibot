(ns yetibot.test.commands.scala
  (:require
    [midje.sweet :refer [fact => truthy]]
    [yetibot.core.parser :refer [parser parse-and-eval]]
    [yetibot.commands.scala :refer :all]))

(def eval-and-extract (comp extract-result try-scala))

(comment
  ;; Scala API is broken. TODO Find a replacement:
  ;; https://github.com/yetibot/yetibot/issues/665

  (fact should-successfully-extract-valid-expression-test-scala
    (eval-and-extract "1 + 1; 2 + 2") => '("2: Int" "4: Int"))

  (fact should-return-not-found-for-unknown-variables
    (eval-and-extract "a; b") => '("not found: value a" "not found: value b"))

  (fact should-return-error-for-invalid-operations
    (eval-and-extract "1 / 0") => '("java.lang.ArithmeticException: / by zero"))

  (fact should-evaluate-properties
    (eval-and-extract "scala.util.Properties.versionString") => '("\"version 2.11.8\": String"))

  (fact should-allow-evaluation-with-pipes
    (parser "scala \"true || false\"") => truthy)

  )
