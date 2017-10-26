(ns yetibot.commands.base64
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.data.codec.base64 :as b64]))

(defn encode-cmd
  "base64 encode <string>"
  [{[_ s] :match}]
  (try
    (String. (b64/encode (.getBytes s)))
    (catch Exception _ "Oops! Can't encode that.")))


(defn decode-cmd
  "base64 decode <string>"
  [{[_ s] :match}]
  (try
    (String. (b64/decode (.getBytes s)))
    (catch Exception _ "Oops! Cant' decode that.")))

(cmd-hook #"base64"
          #"^encode\s(.+)" encode-cmd
          #"^decode\s(.+)" decode-cmd)