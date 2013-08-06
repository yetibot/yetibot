(ns yetibot.parser
  (:require [instaparse.core :as insta]))

(def parse
  (insta/parser
    "expr = cmd <space> (<pipe> <space> cmd)+ | cmd
     cmd = <' '>* word-sequence <' '>*
     sub-expr = <backtick> (expr | sub-expr) <backtick>
     <word-sequence> = word (<space>* word)*
     <word> = sub-expr | #'[^` |]+'
     <space> = #'[ ]+'
     <pipe> = #'[|]'
     <backtick> = #'`'"))

; Note: Nested backticks wouldn't really work - there needs to be an opening and
; closing backtick for the parser to realize that they're actually nested and not
; just 2 separate backticked expressions 
; Possible syntaxes:
; echo `echo (catfact)`
; echo `echo `catfact''
;
; Example of parsing error due to ambiguity:
; (parser
;   "urban random | buffer | echo `meme wizard: what is `buffer peek | head`!!?` foo bar")
