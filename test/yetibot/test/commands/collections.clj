(ns yetibot.test.commands.collections
  (:require
    [yetibot.commands.collections :refer :all]
    [clojure.test :refer :all]))

(deftest grep-data-structure-test
  (is (= (grep-data-structure #"bar" [["foo" 1] ["bar" 2]])
         [[["bar" 2]]])))

(deftest slide-context-test
  (is (= (slide-context (range 10) 3 2)
         [1 2 3 4 5]))
  (is (= (slide-context (range 10) 1 2)
         [0 1 2 3])
      "It should be shorter if there aren't enough results before")
  (is (= (slide-context (range 10) 9 3)
         [6 7 8 9])
      "It should be shorter if there aren't enough results at the end"))

(deftest sliding-filter-test
  (is (= (sliding-filter 1 #(> % 4) (range 10))
         [[4 5 6] [5 6 7] [6 7 8] [7 8 9] [8 9]]))
  (is (= (sliding-filter 1 odd? (range 6 10))
         [[6 7 8] [8 9]])))

(deftest grep-around-test
  (is (= (grep-data-structure
           #"foo"
           ["bar" "lol" "foo" "baz" "qux"]
           {:context 1})
         [["lol" "foo" "baz"]])))

(deftest grep-cmd-test
  (is (= (grep-cmd {:args "foo"
                    :opts ["foo" "bar"]})
         [["foo"]]))
  (is (= (grep-cmd {:args (re-find #"-C\s+(\d+)\s+(.+)" "-C 1 baz")
                    :opts ["foo" "bar" "baz"]})
         [["bar" "baz"]])))
