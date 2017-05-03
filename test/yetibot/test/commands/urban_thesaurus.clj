(ns yetibot.test.commands.urban-thesaurus
  (:require
    [yetibot.commands.urban-thesaurus :refer :all]
    [clojure.test :refer :all]))

(deftest return-words-from-api-response
  (is (not-empty
        (urbanthes-cmd {:match "programmer"}))))
