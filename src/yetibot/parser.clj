(ns yetibot.parser
  (:require
    [clojure.core.match :refer [match]]
    [yetibot.interpreter :refer [handle-expr]]
    [clojure.string :refer [join]]
    [instaparse.core :as insta]))

(def parser
  "Major components of the parser:
   expr     - the top-level expression made up of cmds and sub-exprs. When multiple
              cmds are present, it implies they should be sucessively piped.
   cmd      - a single command consisting of words.
   sub-expr - a backticked or $(..)-style sub-expression to be evaluated inline.
   parened  - a grouping of words wrapped in parenthesis, explicitly tokenized to
              allow parenthesis in cmds and disambiguate between sub-expression
              syntax.
   literal  - a grouping of any characters but quote surrounded by quotes.
              Allows the use of pipes and any other special characters to be
              treated as literal rather than being parsed."
  (insta/parser
    "expr = cmd (<space> <pipe> <space> cmd)*
     cmd = words
     <sub-expr> = <backtick> expr <backtick> | nestable-sub-expr
     <nestable-sub-expr> = <dollar> <lparen> expr <rparen>
     words = space* word (space* word)*
     <word> = sub-expr | parened | word-chars | lparen | rparen | quote | literal
     <word-chars> = #'[^ `$()|\"]+'
     parened = lparen words rparen
     <quote> = '\"'
     literal = quote #'[^\"]+' quote
     space = ' '
     <pipe> = #'[|]'
     <dollar> = <'$'>
     <lparen> = '('
     <rparen> = ')'
     <backtick> = <'`'>"))

(def transformer
  (partial
    insta/transform
    {:words (fn [& words] (join words))
     :literal str
     :space str
     :parened str
     :cmd identity
     :expr handle-expr}))

(defn parse-and-eval [input]
  (-> input parser transformer))
