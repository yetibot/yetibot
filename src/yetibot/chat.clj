(ns yetibot.chat
  (:require
    [yetibot.util.format :as fmt]))

(defn send-msg-for-each [msgs send-msg-fn]
  (println (str "send" (count msgs) "messages"))
  (println msgs)
  (doseq [m msgs] (send-msg-fn m)))

(defn contains-image-url-lines?
  "Returns true if the string contains an image url on its own line, separated from
   other characters by a newline"
  [string]
  (not (empty? (filter #(re-find (re-pattern (str "(?m)^http.*\\." %)) string) ["jpeg" "jpg" "png" "gif"]))))

(defn should-send-msg-for-each?  [d formatted]
  (and
    (coll? d)
    (<= (count d) 30)
    (re-find #"\n" formatted)
    (contains-image-url-lines? formatted)))

(defn chat-data-structure [d send-msg-fn send-paste-fn]
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
        (should-send-msg-for-each? d formatted) (send-msg-for-each flattened send-msg-fn)
        ; send the message with newlines as a paste
        (re-find #"\n" formatted) (send-paste-fn formatted)
        ; send as regular message
        :else (send-msg-fn formatted)))))
