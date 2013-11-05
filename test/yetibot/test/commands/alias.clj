(ns yetibot.test.commands.alias
  (:require
    [clojure.test :refer :all]
    [clojure.string :refer [split]]
    [yetibot.commands.alias :refer :all]))

(let [args ["a = random \\| echo hi"
            "b = echo hi"
            "c = random \\| echo http://foo.com?bust=%s"]])
