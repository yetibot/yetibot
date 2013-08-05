(ns yetibot.parser
  (:require [instaparse.core :as insta]))

; [:number "123"]

(def parser
  (insta/parser
    "expr = cmd <space> (<pipe> <space> cmd)+ | cmd
     cmd = <' '>* space-separated-words <' '>*
     sub-expr = <backtick> (expr | sub-expr) <backtick>

     <space-separated-words> = word (<space> word)*
     <word> = sub-expr | #'[^` |]+'
     <space> = #'[ ]+'
     <pipe> = #'[|]'
     <backtick> = #'`'
     "))

(parser "echo `echo `catfact` `catfact``")
[:expr [:cmd "echo" [:sub-expr [:expr [:cmd "echo" [:sub-expr [:expr [:cmd "catfact"]]] [:sub-expr [:expr [:cmd "catfact"]]]]]]]]

(parser "`catfact`")
[:expr [:cmd "hello" [:sub-expr [:expr [:cmd "foo"]]] "there"]]

[:expr
 [:cmd "hello"
  [:sub-expr [:backtick "`"] [:expr [:cmd "foo"]] [:backtick "`"]]
  "there"]]

(parser "hello there")
[:expr [:cmd "hello" "there"] [:cmd "123"] [:cmd "how" "are" "you"]]
