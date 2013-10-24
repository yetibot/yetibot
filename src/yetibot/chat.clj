(ns yetibot.chat
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :refer [blank?]]
    [yetibot.util.format :as fmt]))

; the chat adapter should set this before firing off command handlers.
; expected keys are :paste, :msg
; TODO: with-scope might be nicer than binding dynamic messaging-fns
(def ^:dynamic *messaging-fns*)

(defn- mk-sender [sender-key]
  (fn [msg]
    (let [msg (str msg)]
      ((sender-key *messaging-fns*) (if (blank? msg) "No results" msg)))))

(def send-msg (mk-sender :msg))
(def send-paste (mk-sender :paste))

(defn send-msg-for-each [msgs]
  (doseq [m msgs] (send-msg m)))

(defn contains-image-url-lines?
  "Returns true if the string contains an image url on its own line, separated from
   other characters by a newline"
  [string]
  (not (empty? (filter #(re-find (re-pattern (str "(?m)^http.*\\." %)) string)
                       ["jpeg" "jpg" "png" "gif"]))))

(defn should-send-msg-for-each?  [d formatted]
  (and (coll? d)
       (<= (count d) 30)
       (re-find #"\n" formatted)
       (contains-image-url-lines? formatted)))

(defn chat-data-structure [d]
  "Formatters to send data structures to chat.
   If `d` is a nested data structure, it will attempt to recursively flatten
   or merge (if it's a map)."
  (when-not (:suppress (meta d))
    (let [[formatted flattened] (fmt/format-data-structure d)]
      (info "formatted is " formatted)
      (info "flattened is " flattened)
      (cond
        ; send each item in the coll as a separate message if it contains images and
        ; the total length of the collection is less than 20
        (should-send-msg-for-each? d formatted) (send-msg-for-each flattened)
        ; send the message with newlines as a paste
        (re-find #"\n" formatted) (send-paste formatted)
        ; send as regular message
        :else (send-msg formatted)))))
