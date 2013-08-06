(ns yetibot.test.parser
  (:require [yetibot.parser :refer :all]
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
     [:expr [:cmd "echo" [:sub-expr [:expr [:cmd "catfact"]]]] [:cmd "echo" "It" "is" "known:"]])
    "Sub-expressions should be parsed"))

; TODO: nested backticks, see note on limitiations in parse.clj
; (deftest nested-sub-expr-test
;   (is
;     (=
;      (parse
;        "urban random | buffer | echo `meme wizard: what is `buffer peek | head`?`
;         `meme chemistry: a `buffer peek | head` is `buffer peek | head 2 | tail``")
