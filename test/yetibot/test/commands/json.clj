(ns yetibot.test.commands.json
  (:require
    [midje.sweet :refer [fact =>]]
    [yetibot.commands.json :refer :all]))

(fact test-should-convert-keys-to-keywords-when-parsing-json
  (let [args {:match ["" "{\"key\": \"value\"}"]}]
    (json-parse-cmd args) => {:key "value"}))
