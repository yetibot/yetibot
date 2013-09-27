(ns yetibot.parser
  (:require
    [yetibot.interpreter :refer [handle-expr]]
    [clojure.string :refer [join]]
    [instaparse.core :as insta]))

(def parser
  "expr     - the top-level expression made up of cmds and sub-exprs. When multiple
              cmds are present, it implies they should be sucessively piped.
   cmd      - a single command consisting of words.
   sub-expr - a backticked or $(..)-style sub-expression to be evaluated inline.
   parened  - a grouping of words wrapped in parenthesis, explicitly tokenized to
              allow parenthesis in cmds and disambiguate between sub-expression
              syntax."
  (insta/parser
    "expr = cmd (<space> <pipe> <space> cmd)*
     cmd = words
     <sub-expr> = <backtick> expr <backtick> | nestable-sub-expr
     <nestable-sub-expr> = <dollar> <lparen> expr <rparen>
     words = word (<space>* word)*
     <word> = sub-expr | parened | word-chars
     <word-chars> = #'[^ `$()|]+'
     parened = lparen words rparen
     <space> = #'[ ]+'
     <pipe> = #'[|]'
     <dollar> = <'$'>
     <lparen> = '('
     <rparen> = ')'
     <backtick> = <'`'>"))

(def transformer
  (partial
    insta/transform
    {:words (fn [& args] (join " " args))
     :parened str
     :cmd identity
     :expr handle-expr}))


;; There is a bug in parsing/evaluating. In an expression like:
;; "echo $(echo foo)bar" the result should be "foobar" but it's actually "foo bar".
;; This is because when the AST is evaluated the inner "echo foo" is a separate :expr
;; and the string "bar" follows it, siblings in a :words leaf. Possible solution:
;;
;; - multi-pass evaluation where sub exprs get evaluated to another leaf type, like
;; [:words [:evaluated "foo"] "bar"]. The :words flattener would concat "foo" and
;; "bar" without the space. The problem with that is that is then all sub-exprs would
;; lack a space. The problem is the original lack of space information is not
;; preserved.
;;
;; How do we preserve it? Do we abandon parsing :words?
;;
;; Maybe it should only be split into [first & rest] words since the first is the
;; only one that really matters (i.e. it's the cmd).
;;
(defn parse-and-eval [input]
  (-> input parser transformer))
