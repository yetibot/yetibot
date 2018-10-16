(ns yetibot.test.commands.weather
  (:require
   [midje.sweet :refer [facts fact =>]]
   [clojure.string :as str]
   [yetibot.commands.weather :refer :all]))

(facts "about postal code parsing"
       (fact "US zip codes - New York City"
             (chk-postal-code "10016")      => ["US" "10016"]
             (chk-postal-code "10016-0001") => ["US" "10016"]
             (chk-postal-code "10016+0001") => ["US" "10016"])
       (fact "Romanian post codes - Bucharest"
             (chk-postal-code "020891")       => ["RO" "020891"]))
