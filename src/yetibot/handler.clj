(ns yetibot.handler
  (:require
    [yetibot.util.format :refer [to-coll-if-contains-newlines]]
    [yetibot.parser :refer [parse-and-eval]]
    [clojure.core.match :refer [match]]
    [clojure.string :refer [join]]
    [clojure.stacktrace :as st]))

(defn handle-unparsed-expr
  "Top-level entry point for parsing and evaluation of commands"
  ([chat-source user body]
   ; For backward compat, support setting user at this level. After deprecating, this
   ; can be removed.
   (prn "handle unparsed expr" chat-source body user)
   (binding [yetibot.interpreter/*current-user* user
             yetibot.interpreter/*chat-source* chat-source]
     (handle-unparsed-expr body)))
  ([body] (parse-and-eval body)))

(defn handle-raw
  "No-op handler for optional hooks"
  [body user])

(defn cmd-reader [& args] (handle-unparsed-expr (join " " args)))
