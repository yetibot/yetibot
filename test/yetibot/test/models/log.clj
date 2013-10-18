(ns yetibot.test.models.log
  (:require
    [yetibot.models.log :refer :all]
    [clojure.test :refer :all]))

(select-keys args [:level :prefix :message])
