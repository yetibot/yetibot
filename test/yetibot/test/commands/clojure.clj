(ns yetibot.test.commands.clojure
  (:require
    [clojure.test :refer :all]
    [yetibot.commands.clojure :refer :all]
    [yetibot.parser :refer [parse-and-eval]]))

(deftest test-clj
  (is (= (parse-and-eval "clj (+ 1 2)") "3")))
