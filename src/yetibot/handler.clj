(ns yetibot.handler
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.util :refer [with-fresh-db]]
    [yetibot.util.format :refer [to-coll-if-contains-newlines]]
    [yetibot.parser :refer [parse-and-eval]]
    [clojure.core.match :refer [match]]
    [yetibot.chat :refer [chat-data-structure]]
    [clojure.string :refer [join]]
    [clojure.stacktrace :as st]))

(defn handle-unparsed-expr
  "Top-level entry point for parsing and evaluation of commands"
  ([chat-source user body]
   ; For backward compat, support setting user at this level. After deprecating, this
   ; can be removed.
   (info "handle unparsed expr:" chat-source body user)
   (binding [yetibot.interpreter/*current-user* user
             yetibot.interpreter/*chat-source* chat-source]
     (handle-unparsed-expr body)))
  ([body] (parse-and-eval body)))


(def ^:private exception-format "👮 %s 👮")

; TODO: move handle-unparsed-expr calls out of the adapters and call it from here
; instead
(defn handle-raw
  "No-op handler for optional hooks.
   Expected event-types are:
   :message
   :leave
   :enter
   :sound
   :kick"
  [chat-source user event-type body]
  ; only :message has a body
  (when body
    ; see if it looks like a command
    (when-let [[_ body] (re-find #"^\!(.+)" body)]
      (with-fresh-db
        (try
          (chat-data-structure
            (handle-unparsed-expr chat-source user body))
          (catch Exception ex
            (error
              "error handling expression:" body
              (with-out-str
                (newline)
                (st/print-stack-trace (st/root-cause ex) 50)))
            (chat-data-structure (format exception-format ex))))))))

(defn cmd-reader [& args] (handle-unparsed-expr (join " " args)))
