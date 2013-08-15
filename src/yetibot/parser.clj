(ns yetibot.parser
  (:require [instaparse.core :as insta]))

(def parse
  (insta/parser
    "expr = cmd <space> (<pipe> <space> cmd)+ | cmd
     cmd = <' '>* word-sequence <' '>*
     <sub-expr> = <backtick> expr <backtick> | nestable-sub-expr
     <nestable-sub-expr> = dollar lparen (expr | nestable-sub-expr) rparen
     <word-sequence> = word (<space>* word)*
     <word> = sub-expr / #'[^` |$()]+'
     <space> = #'[ ]+'
     <pipe> = #'[|]'
     <dollar> = <'$'>
     <lparen> = <'('>
     <rparen> = <')'>
     <backtick> = <'`'>"))
