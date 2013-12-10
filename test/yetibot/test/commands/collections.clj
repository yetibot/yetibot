(ns yetibot.test.commands.collections
  (:require
    [yetibot.commands.collections :refer :all]
    [clojure.test :refer :all]))

(deftest grep-data-structure-test
  (is (= (grep-data-structure #"bar" [["foo" 1] ["bar" 2]])
         '(["bar" 2]))))
