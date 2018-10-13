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
      (to-pirate "HeLlO WoRlD admin!!")    => "Ahoy WoRlD helm!!")

(fact "suffix-flavor respects supplied probability constant"
      (suffix-flavor "foo" 0)  => "foo"
      (suffix-flavor "foo" 1)  => #"^foo,\s+[^\s]+"
      (suffix-flavor "foo." 1) => #"^foo,\s+[^\s]+?\.$")

(def test-str "the quick brown fox jumps over the lazy dog")

(fact "slurrr respects supplied probability constant"
      (slurrr test-str 0) => test-str
      (slurrr test-str 1) => #"([alr])\1")
