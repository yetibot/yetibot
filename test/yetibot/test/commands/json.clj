(ns yetibot.test.commands.json
  (:require
    [clojure.data.json :as json]
    [midje.sweet :refer [fact =>]]
    [yetibot.commands.json :refer :all]))

(fact convert-keys-to-keywords-when-parsing-json
  (let [args {:match ["" "{\"key\": \"value\"}"]}]
    (json-parse-cmd args) => {:key "value"}))

(fact return-value-of-json-path
  (let [args {:match ["" "$.key"] :opts (json/read-str "{\"key\": \"value\"}" :key-fn keyword)}]
    (json-path-cmd args) => "value"))

(fact return-error-when-json-is-invalid
  (let [args {:match ["" "$.key"] :opts "{}"}]
    (json-path-cmd args) => "Not a valid json data structure:\"{}\""))
