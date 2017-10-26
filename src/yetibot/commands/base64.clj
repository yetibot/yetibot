= (ns yetibot.commands.base64
    (:require
      [yetibot.core.hooks :refer [cmd-hook]]
      [clojure.data.codec.base64 :as b64]))

(defn into-bytes
  "turns a string into a byte array"
  [s]
  (byte-array (map (comp byte int) s)))

(defn encode-cmd
  "base64 encode a string"
  [{[_ s] :match}]
  (try
    (String. (b64/encode (into-bytes s)))
    (catch Exception _ "Oops! Can't encode that.")))


(defn decode-cmd
  "base64 decode a string"
  [{[_ s] :match}]
  (try
    (String. (b64/decode (into-bytes s)))
    (catch Exception _ "Oops! Cant' decode that.")))

(cmd-hook #"base64"
          #"^encode\s(.+)" encode-cmd
          #"^decode\s(.+)" decode-cmd)