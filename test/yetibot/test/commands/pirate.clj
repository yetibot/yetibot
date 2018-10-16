(ns yetibot.test.commands.pirate
  (:require
   [midje.sweet :refer [facts fact =>]]
   [clojure.string :as str]
   [yetibot.commands.pirate :refer :all]))

(facts "about lower-level wrapper fns"
       (fact "wrap-punctuation preserves punctuation even when wrapped fn alters str"
             ((wrap-punctuation (fn [_] "foo")) "bar!") => "foo!")
       (fact "wrap-capitalization preserves captilization when when wrapped fn lower-cases str"
             ((wrap-capitalization str/lower-case) "Foo") => "Foo"))

(fact "to-pirate translates strings, preserving captilization and punctuation"
      (to-pirate "jello world")            => "jello world"
      (to-pirate "hello world")            => "ahoy world"
      (to-pirate "hello world admin")      => "ahoy world helm"
      (to-pirate "hello world admin!")     => "ahoy world helm!"
      (to-pirate "hello world admin!?.,:") => "ahoy world helm!?.,:"
      (to-pirate "!?.,:")                  => "!?.,:"
      (to-pirate "Hello world admin")      => "Ahoy world helm"
      (to-pirate "Hello World Admin")      => "Ahoy World Helm"
      (to-pirate "HeLlO WoRlD admin")      => "Ahoy WoRlD helm"
      (to-pirate "HeLlO WoRlD admin!!")    => "Ahoy WoRlD helm!!"
      (to-pirate "hello world   admin")    => "ahoy world   helm")

(fact "suffix-flavor suffixes something"
      (suffix-flavor "foo")  => #"^foo,\s+[^\s]+"
      (suffix-flavor "foo.") => #"^foo,\s+.+\.$")

(def test-str "the quick brown fox jumps over the lazy dog")

(fact "slurrr permutes text"
      (slurrr test-str) => #"([alr])\1")

(fact "if-prob respects probability constant"
      (if-prob 0 inc 0) => 0
      (if-prob 0 inc 1) => 1)
