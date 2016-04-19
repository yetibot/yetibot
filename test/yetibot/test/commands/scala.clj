(ns yetibot.test.commands.scala
  (:require
    [clojure.test :refer :all]
    [yetibot.core.parser :refer [parser parse-and-eval]]
    [yetibot.commands.scala :refer :all]))

(def eval-and-extract (comp extract-result try-scala))

(deftest test-scala
  (is (= '("2: Int" "4: Int")
         (eval-and-extract "1 + 1; 2 + 2"))
      "Valid expressions")
  (is (= '("not found: value a" "not found: value b")
         (eval-and-extract "a; b"))
      "Compiler errors")
  (is (= '("java.lang.ArithmeticException: / by zero")
         (eval-and-extract "1 / 0"))
      "Runtime errors")
  (is (= '("\"version 2.11.8\": String")
         (eval-and-extract "scala.util.Properties.versionString"))
      "scalakata.EString"))

(deftest with-pipes
  (parser "scala \"true || false\""))
