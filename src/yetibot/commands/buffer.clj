(ns yetibot.commands.buffer
  (:require [yetibot.core.hooks :refer [cmd-hook cmd-unhook]]))

(defonce buffer (atom []))

(defn show-buffer []
  (let [b @buffer]
    (if (empty? b) "The buffer is empty" b)))

(defn buffer-cmd
  "buffer # accumulate arguments or piped contents into a buffer"
  [{:keys [args opts]}]
  (when (coll? opts) (swap! buffer into opts))
  (swap! buffer conj args))

(defn flush-buffer
  "buffer flush # flush the buffer, outputting its contents"
  [_] (let [b (show-buffer)]
        (reset! buffer [])
        b))

(defn peek-buffer
  "buffer peek # peek at the buffer's contents without flushing"
  [_] (show-buffer))

(cmd-hook ["buffer" #"^buffer$"]
          #"peek" peek-buffer
          #"flush" flush-buffer
          _ buffer-cmd)
