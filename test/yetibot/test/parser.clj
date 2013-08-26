(ns yetibot.test.parser
  (:require [yetibot.parser :refer :all]
            [instaparse.core :as insta]
            [clojure.test :refer :all]))

(deftest single-cmd-test
  (is (= (parser "uptime")
         [:expr [:cmd [:words "uptime"]]]))
  (is (= (parser "echo qux") [:expr [:cmd [:words "echo" "qux"]]])
      "Single commands should be parsed"))

(deftest piped-cmd-test
  (is
    (= (parser "echo hello | echo bar")
       [:expr [:cmd [:words "echo" "hello"]] [:cmd [:words "echo" "bar"]]])
    "Piped commands should be parsed"))

(deftest sub-expr-test
  (is
    (=
     (parser "echo `catfact` | echo It is known:")
     [:expr
      [:cmd [:words "echo" [:expr [:cmd [:words "catfact"]]]]]
      [:cmd [:words "echo" "It" "is" "known:"]]])
    "Backtick sub-expressions should be parsed")
  (is
    (=
     (parser "echo $(random)")
     [:expr [:cmd [:words "echo" [:expr [:cmd [:words "random"]]]]]])
    "Standard sub-expressions should be parsed"))

(deftest nested-sub-expr-test
  (is
    (=
     (parser "random | buffer | echo `number $(buffer | peek)`")
     [:expr [:cmd [:words "random"]] [:cmd [:words "buffer"]] [:cmd [:words "echo" [:expr [:cmd [:words "number" [:expr [:cmd [:words "buffer"]] [:cmd [:words "peek"]]]]]]]]])
    "Nested sub-expressions should be parsed")
  (is
    (=
     (parser
       "urban random | buffer | echo `meme wizard: what is $(buffer peek | head)?`
        `meme chemistry: a $(buffer peek | head) is $(buffer peek | head 2 | tail)`")
     [:expr
      [:cmd [:words "urban" "random"]]
      [:cmd [:words "buffer"]]
      [:cmd
       [:words
        "echo"
        [:expr
         [:cmd
          [:words
           "meme"
           "wizard:"
           "what"
           "is"
           [:expr [:cmd [:words "buffer" "peek"]] [:cmd [:words "head"]]]
           "?"]]]
        "\n"
        [:expr
         [:cmd
          [:words
           "meme"
           "chemistry:"
           "a"
           [:expr [:cmd [:words "buffer" "peek"]] [:cmd [:words "head"]]]
           "is"
           [:expr
            [:cmd [:words "buffer" "peek"]]
            [:cmd [:words "head" "2"]]
            [:cmd [:words "tail"]]]]]]]]]
     )
    "Complex nested sub-expressions with newlines should be parsed"))
