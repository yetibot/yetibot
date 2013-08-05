(ns yetibot.parser
  (:require [instaparse.core :as insta]))

; [:number "123"]

(def parser
  (insta/parser
    "expr = cmd <space> (<pipe> <space> cmd)+ | cmd
     cmd = <' '>* space-separated-words <' '>*

     <space-separated-words> = word (<space> word)*
     <word> = #'[^ |]+'
     <space> = #'[ ]+'
     <pipe> = #'[|]'
     "))

(parser "hello there")
(parser "hello there | 123 | how are you")
