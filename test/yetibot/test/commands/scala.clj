(ns yetibot.test.commands.scala
  (:require
    [clojure.test :refer :all]
    [yetibot.commands.scala :refer :all]))

(def eval-and-extract (comp extract-result try-scala))

(deftest test-scala
  (is (= '("2" "4")
         (eval-and-extract "1 + 1; 2 + 2"))
      "Valid expressions")
  (is (= '("not found: value a" "not found: value b")
         (eval-and-extract "a; b"))
      "Compiler errors")
  (is (= '("java.lang.ArithmeticException: / by zero")
         (eval-and-extract "1 / 0"))
      "Runtime errors"))

