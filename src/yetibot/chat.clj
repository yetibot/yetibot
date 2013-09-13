(ns yetibot.chat
  (:require
    [yetibot.util.format :as fmt]))

; the chat adapter should set this before firing off command handlers.
; expected keys are :paste, :msg
; TODO: with-scope might be nicer than binding dynamic messaging-fns
(def ^:dynamic *messaging-fns*)

(defn send-msg-for-each [msgs]
  (prn "send" (count msgs) "messages")
  (doseq [m msgs] ((:msg *messaging-fns*) m)))

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
      (prn "formatted is " formatted)
      (prn "flattened is " flattened)
      (cond
        ; send each item in the coll as a separate message if it contains images and
        ; the total length of the collection is less than 20
        (should-send-msg-for-each? d formatted) (send-msg-for-each flattened)
        ; send the message with newlines as a paste
        (re-find #"\n" formatted) ((:paste *messaging-fns*) formatted)
        ; send as regular message
        :else ((:msg *messaging-fns*) formatted)))))
