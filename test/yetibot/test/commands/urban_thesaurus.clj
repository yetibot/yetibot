(ns yetibot.test.commands.urban-thesaurus
  (:require
    [yetibot.commands.urban-thesaurus :refer :all]
    [midje.sweet :refer [fact => anything just]]
    [yetibot.core.util.http :refer [fetch]]
    [clojure.data.json :as json]))

(fact return-all-words-from-api-response
  (urbanthes-cmd {:match "programmer"}) => (just "test" "word")
  (provided
    (fetch anything) => (json/write-str [{:word "test"} {:word "word"}])))

(fact return-empty-list-for-unknown-word
  (urbanthes-cmd {:match "abcdef"}) => empty?
  (provided
    (fetch anything) => (json/write-str [])))
