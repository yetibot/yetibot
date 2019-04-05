(ns yetibot.test.commands.pirate
  (:require
   [midje.sweet :refer [facts fact =>]]
   [yetibot.core.midje :refer [value data]]
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
      (suffix-flavor "foo bar")    => #"^foo bar,\s+[^\s]+"
      (suffix-flavor "foo bar.")   => #"^foo bar,\s+.+\.$"
      (suffix-flavor "foo bar. ")  => #"^foo bar,\s+.+\.$"
      (suffix-flavor "foo bar.. ") => #"^foo bar,\s+.+\.\.$")

(def test-str "the quick brown fox jumps over the lazy dog")

(fact "slurrr permutes text"
      (slurrr test-str) => #"([alr])\1")

(fact "if-prob respects probability constant"
      (if-prob 0 inc 0) => 0
      (if-prob 0 inc 1) => 1)


(def test-cmd-input {:match test-str})

(fact "pirate-cmd has well-formed return"
      (pirate-cmd test-cmd-input) => map?
      (pirate-cmd test-cmd-input) => (value string?)
      (pirate-cmd test-cmd-input) => (data map?))

(fact "pirate-cmd data result keys show original string and differing translation"
      (pirate-cmd test-cmd-input) => (data (fn [{s :original}] (= s test-str)))
      (pirate-cmd test-cmd-input) => (data (fn
                                             [{:keys [original translation]}]
                                             (not= original translation))))
