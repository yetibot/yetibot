(ns yetibot.test.parser
  (:require [yetibot.parser :refer :all]
            [instaparse.core :as insta]
            [clojure.test :refer :all]))

(deftest single-cmd-test
  (is (= (parse "echo qux") [:expr [:cmd "echo" "qux"]])
      "Single commands should be parsed"))

(deftest piped-cmd-test
  (is
    (= (parse "echo hello | echo bar")
       [:expr [:cmd "echo" "hello"] [:cmd "echo" "bar"]])
    "Piped commands should be parsed"))

(deftest sub-expr-test
  (is
    (=
     (parse "echo `catfact` | echo It is known:")
     [:expr
      [:cmd "echo" [:expr [:cmd "catfact"]]]
      [:cmd "echo" "It" "is" "known:"]])
    "Backtick sub-expressions should be parsed")
  (is
    (=
     (parse "echo $(random)")
     [:expr [:cmd "echo" [:expr [:cmd "random"]]]])
    "Standard sub-expressions should be parsed"))

(deftest nested-sub-expr-test
  (is
    (=
     (parse "random | buffer | echo `number $(buffer | peek)`")
     [:expr
      [:cmd "random"]
      [:cmd "buffer"]
      [:cmd
       "echo"
       [:expr [:cmd "number" [:expr [:cmd "buffer"] [:cmd "peek"]]]]]])
    "Nested sub-expressions should be parsed")
  (is
    (=
     (parse
       "urban random | buffer | echo `meme wizard: what is $(buffer peek | head)?`
        `meme chemistry: a $(buffer peek | head) is $(buffer peek | head 2 | tail)`")
     [:expr
      [:cmd "urban" "random"]
      [:cmd "buffer"]
      [:cmd
       "echo"
       [:expr
        [:cmd
         "meme"
         "wizard:"
         "what"
         "is"
         [:expr [:cmd "buffer" "peek"] [:cmd "head"]]
         "?"
         [:expr [:cmd "\n"]]
         "meme"
         "chemistry:"
         "a"
         [:expr [:cmd "buffer" "peek"] [:cmd "head"]]
         "is"
         [:expr [:cmd "buffer" "peek"] [:cmd "head" "2"] [:cmd "tail"]]]]]])
    "Complex nested sub-expressions with newlines should be parsed"))
