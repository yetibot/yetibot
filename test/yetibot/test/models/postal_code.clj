(ns yetibot.test.models.postal-code
  (:require
   [midje.sweet :refer [facts fact =>]]
   [yetibot.models.postal-code :refer :all]))

(facts "about postal code parsing"
       (fact "postal codes in the US - New York City"
             (chk-postal-code "10016")      => ["US" "10016"]
             (chk-postal-code "10016-0001") => ["US" "10016"]
             (chk-postal-code "10016+0001") => ["US" "10016"])
       (fact "postal codes in Romania - Bucharest"
             (chk-postal-code "020891")     => ["RO" "020891"])
       (fact "postal codes in Brazil - Brasília"
             (chk-postal-code "70000-000")  => ["BR" "70000-000"])
       (fact "postal codes in The Netherlands - Amsterdam"
             (chk-postal-code "1000 BB")    => ["NL" "1000 BB"]
             (chk-postal-code "1000BB")     => ["NL" "1000 BB"]
             (chk-postal-code "1000bb")     => ["NL" "1000 BB"]
             (chk-postal-code "1000 SA")    => nil)
       (fact "postal codes in The UK"
             (chk-postal-code "EC1A 1BB")   => ["GB" "EC1A 1BB"]
             (chk-postal-code "EC1A1BB")    => ["GB" "EC1A 1BB"]
             (chk-postal-code "ec1a1bb")    => ["GB" "EC1A 1BB"]
             (chk-postal-code "W1A 0AX")    => ["GB" "W1A 0AX"]
             (chk-postal-code "W1A0AX")     => ["GB" "W1A 0AX"]
             (chk-postal-code "w1a0ax")     => ["GB" "W1A 0AX"]
             (chk-postal-code "M1 1AE")     => ["GB" "M1 1AE"]
             (chk-postal-code "M11AE")      => ["GB" "M1 1AE"]
             (chk-postal-code "m11ae")      => ["GB" "M1 1AE"]
             (chk-postal-code "B33 8TH")    => ["GB" "B33 8TH"]
             (chk-postal-code "B338TH")     => ["GB" "B33 8TH"]
             (chk-postal-code "b338th")     => ["GB" "B33 8TH"]
             (chk-postal-code "CR2 6XH")    => ["GB" "CR2 6XH"]
             (chk-postal-code "CR26XH")     => ["GB" "CR2 6XH"]
             (chk-postal-code "cr26xh")     => ["GB" "CR2 6XH"]
             (chk-postal-code "DN55 1PT")   => ["GB" "DN55 1PT"]
             (chk-postal-code "DN551PT")    => ["GB" "DN55 1PT"]
             (chk-postal-code "dn551pt")    => ["GB" "DN55 1PT"]
             (chk-postal-code "GIR 0AA")    => ["GB" "GIR 0AA"]
             (chk-postal-code "GIR0AA")     => ["GB" "GIR 0AA"]
             (chk-postal-code "gir 0aa")    => ["GB" "GIR 0AA"])
       (fact "postal codes in Australia - Canberra"
             (chk-postal-code "2600")       => ["AU" "2600"]))

(facts "about test lookup ordering"
       (fact "AU matches before PH"
             (chk-postal-code "1234")      => ["AU" "1234"])
       (fact "PH matches instead of AU when CC is supplied"
             (chk-postal-code "1234" "PH") => ["PH" "1234"]))
