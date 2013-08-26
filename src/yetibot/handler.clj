(ns yetibot.handler
  (:require
    [yetibot.util :refer [psuedo-format]]
    [yetibot.util.format :refer [to-coll-if-contains-newlines]]
    [yetibot.models.users :as users]
    [yetibot.parser :refer [parse-and-eval]]
    [yetibot.campfire :as cf]
    [clojure.core.match :refer [match]]
    [clojure.string :refer [join]]
    [clojure.stacktrace :as st]))

(defn handle-unparsed-expr
  "Top-level entry point for parsing and evaluation of commands"
  ([body user]
   ; For backward compat, support setting user at this level. After deprecating, this
   ; can be removed.
   (binding [yetibot.interpreter/*current-user* user] (handle-unparsed-expr body)))
  ([body] (parse-and-eval body)))

(def exception-format ":cop::cop: %s :cop::cop:")

(defn handle-text-message [json]
  "Parse a `TextMessage` campfire event into a command and its args"
  (try
    (let [user (users/get-user (:user_id json))]
      (if-let [[_ body] (re-find #"\!(.+)" (:body json))]
        (binding [yetibot.interpreter/*current-user* user]
          (cf/chat-data-structure (handle-unparsed-expr body user)))))
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
  (handle-unparsed-expr (join " " args) nil))
