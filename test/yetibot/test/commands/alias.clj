(ns yetibot.test.commands.alias
  (:require
    [clojure.test :refer :all]
    [clojure.string :refer [split]]
    [yetibot.commands.alias :refer :all]))

(let [args ["a = random \\| echo hi"
            "b = echo hi"
            "c = random \\| echo http://foo.com?bust=%s"]])

(deftest alias-test
  ; examples
  "alias foo = echo %1"
  "alias weatherzip = \"weather %1 | head %2 | tail\""

  )
