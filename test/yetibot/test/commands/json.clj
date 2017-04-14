(ns yetibot.test.commands.json
  (:require
    [clojure.test :refer :all]
    [yetibot.commands.json :refer :all]))

(deftest test-should-convert-keys-to-keywords-when-parsing-json
  (let [args {:match ["" "{\"key\": \"value\"}"]}]
    (is (= (json-parse-cmd args) {:key "value"}))))
