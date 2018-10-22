(ns yetibot.test.models.postal-code
  (:require
   [midje.sweet :refer [facts fact =>]]
   [yetibot.models.postal-code :refer :all]))

(facts "about postal code parsing"
       (fact "postal codes in the US - New York City"
             (chk-postal-code "10016")      => ["10016" "US"]
             (chk-postal-code "10016-0001") => ["10016" "US"]
             (chk-postal-code "10016+0001") => ["10016" "US"])
       (fact "postal codes in Romania - Bucharest"
             (chk-postal-code "020891")     => ["020891" "RO"])
       (fact "postal codes in Brazil - Brasília"
             (chk-postal-code "70000-000")  => ["70000-000" "BR"])
       (fact "postal codes in The Netherlands - Amsterdam"
             (chk-postal-code "1000 BB")    => ["1000 BB" "NL"]
             (chk-postal-code "1000BB")     => ["1000 BB" "NL"]
             (chk-postal-code "1000bb")     => ["1000 BB" "NL"]
             (chk-postal-code "1000 SA")    => nil)
       (fact "postal codes in The UK"
             (chk-postal-code "EC1A 1BB")   => ["EC1A 1BB" "GB"]
             (chk-postal-code "EC1A1BB")    => ["EC1A 1BB" "GB"]
             (chk-postal-code "ec1a1bb")    => ["EC1A 1BB" "GB"]
             (chk-postal-code "W1A 0AX")    => ["W1A 0AX" "GB"]
             (chk-postal-code "W1A0AX")     => ["W1A 0AX" "GB"]
             (chk-postal-code "M1 1AE")     => ["M1 1AE" "GB"]
             (chk-postal-code "M11AE")      => ["M1 1AE" "GB"]
             (chk-postal-code "B33 8TH")    => ["B33 8TH" "GB"]
             (chk-postal-code "B338TH")     => ["B33 8TH" "GB"]
             (chk-postal-code "CR2 6XH")    => ["CR2 6XH" "GB"]
             (chk-postal-code "CR26XH")     => ["CR2 6XH" "GB"]
             (chk-postal-code "DN55 1PT")   => ["DN55 1PT" "GB"]
             (chk-postal-code "DN551PT")    => ["DN55 1PT" "GB"]
             (chk-postal-code "GIR 0AA")    => ["GIR 0AA" "GB"]
             (chk-postal-code "GIR0AA")     => ["GIR 0AA" "GB"]
             (chk-postal-code "gir 0aa")    => ["GIR 0AA" "GB"])
       (fact "postal codes in Australia - Canberra"
             (chk-postal-code "2600")       => ["2600" "AU"])
       (fact "postal codes in Canada"
             (chk-postal-code "K1A 0B1")    => ["K1A 0B1" "CA"]
             (chk-postal-code "K1A0B1")     => ["K1A 0B1" "CA"]
             (chk-postal-code "k1a0b1")     => ["K1A 0B1" "CA"]))

(facts "about test lookup ordering"
       (fact "AU matches before PH"
             (chk-postal-code "1234")      => ["1234" "AU"])
       (fact "PH matches instead of AU when CC is supplied"
             (chk-postal-code "1234" "PH") => ["1234" "PH"]))
