(ns yetibot.handler
  (:require
    [yetibot.util :refer [psuedo-format]]
    [yetibot.util.format :refer [to-coll-if-contains-newlines]]
    [yetibot.models.users :as users]
    [yetibot.parser :refer [parse]]
    [yetibot.campfire :as cf]
    [clojure.core.match :refer [match]]
    [clojure.string :as s]
    [clojure.stacktrace :as st]))

(declare handle-expr)

(defn handle-cmd
  "Hooked entry point for all command handlers. If no handlers intercept, it falls
   back to image search when available."
  [cmd-with-args extra]
  (println "nothing handled" cmd-with-args)
  ; default to looking up a random result from google image search instead of
  ; complaining about not knowing stuff.
  (if (find-ns 'yetibot.commands.image-search)
    (handle-cmd (into ["image"] cmd-with-args) extra)
    (format "I don't know how to handle %s" (s/join " " cmd-with-args))))

(defn expand-sub-exprs
  "Individual args in a :cmd can either be:
   - a plain string, which is returned as-is
   - a sub-expression vector, which is evaluated and returned"
  [arg]
  (prn "expand-sub-exprs" arg)
  (match arg
    (arg :guard string?) arg
    ; it must be a sub-expressions so recursively eval
    (arg :guard vector?) (handle-expr arg)))

(defn expand-cmd
  "Evaluates a single command, evaluating any sub-expressions in the process."
  ([cmd-with-args] (expand-cmd cmd-with-args {}))
  ([cmd-with-args extra]
   ; expand potential sub-expressions
   [(map expand-sub-exprs cmd-with-args) extra]))

(defn pipe-cmds
  "Pipe acc into cmd-with-args by either appending or sending acc as an extra :opts"
  [user acc cmd-vec]
  (let [cmd-with-args (match cmd-vec [:cmd & cmd-with-args] cmd-with-args)
        extra {:user user :raw acc}
        possible-coll-acc (to-coll-if-contains-newlines acc)]
    ; if possible-coll-acc is a string, append acc to args. otherwise send
    ; possible-coll-acc as an extra :coll param and append nothing to cmd-with-args.
    (apply handle-cmd
           (apply expand-cmd
                  (if (coll? possible-coll-acc)
                    [cmd-with-args (conj extra {:opts possible-coll-acc})]
                    [(conj cmd-with-args acc) extra])))))

(defn handle-expr
  "An expression is the top-level command structure, consisting of one or more
   commands. It cannot have any context passed into it."
  [expr user]
  (let [cmds (match expr [:expr & cmds] cmds)]
    (reduce (partial pipe-cmds user) "" cmds)))

(defn handle-unparsed-expr
  ([body] (handle-unparsed-expr body nil))
  ([body user] (handle-expr (parse body) user)))

(def exception-format ":cop::cop: %s :cop:cop:")

(defn handle-text-message [json]
  "Parse a `TextMessage` campfire event into a command and its args"
  (try
    (let [user (users/get-user (:user_id json))]
      (if-let [[_ body] (re-find #"\!(.+)" (:body json))]
        (cf/chat-data-structure (handle-unparsed-expr body user))))
    (catch Exception ex
      (println "Exception inside `handle-text-message`" ex)
      (st/print-stack-trace (st/root-cause ex) 50)
      (cf/send-message (format exception-format ex)))))

(defn handle-campfire-event [json]
  (let [event-type (:type json)]
    (condp = event-type ; Handle the various types of messages
      "TextMessage" (handle-text-message json)
      "PasteMessage" (handle-text-message json)
      (println "No handler for " event-type))))

(defn cmd-reader [& args]
  (handle-unparsed-expr (s/join " " args) nil))

(let [e [:expr [:cmd "echo" "hi"]]]
  (match e
    [:expr & cmds] cmds))
