(ns yetibot.test.commands.weather
  (:require
   [midje.sweet :refer [facts fact =>]]
   [clojure.string :as str]
   [yetibot.commands.weather :refer :all]))

(facts "about postal code parsing"
       (fact "US zip codes - New York City"
             (chk-postal-code "10016")      => ["US" "10016"]
             (chk-postal-code "10016-0001") => ["US" "10016-0001"]
             (chk-postal-code "10016+0001") => ["US" "10016-0001"])
       (fact "postal codes in Romania - Bucharest"
             (chk-postal-code "020891")     => ["RO" "020891"])
       (fact "postal codes in Brazil - Brasília"
             (chk-postal-code "70000-000")  => ["BR" "70000-000"])
       (fact "postal codes in The Netherlands - Amsterdam"
             (chk-postal-code "1000 BB")    => ["NL" "1000 BB"]
             (chk-postal-code "1000 bb")    => ["NL" "1000 BB"]
             (chk-postal-code "1000bb")     => ["NL" "1000 BB"]
             (chk-postal-code "1000 SA")    => nil))

(facts "about test lookup ordering"
       (fact "AU matches before PH"
             (chk-postal-code "1234")      => ["AU" "1234"])
       (fact "PH matches instead of AU when CC is supplied"
             (chk-postal-code "1234" "PH") => ["PH" "1234"]))
