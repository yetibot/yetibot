=(ns yetibot.commands.base64
   (:require
     [yetibot.core.hooks :refer [cmd-hook]]))


(def charset "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
(def index-char (zipmap (range 64) charset))
(def char-index (zipmap (seq (char-array charset)) (range 64)))

(defn encode
  [s]
  (->>
    (map #(int %) s)
    (map #(clojure.pprint/cl-format false "~8,'0b" %))
    (reduce str)
    (partition 6 6 [0 0 0 0 0 0])
    (map #(apply str %))
    (map #(java.lang.Integer/parseInt % 2))
    (map #(get index-char %))
    (reduce str)
    (conj (repeat (rem (* 8 (count s)) 3) "="))
    (reduce str)))

(defn decode
  [s]
  (->>
    (map #(char %) (remove #(= % \newline) s))
    (map #(get char-index % 0))
    (map #(clojure.pprint/cl-format false "~6,'0b" %))
    (reduce str)
    (partition 8)
    (map #(apply str %))
    (map #(java.lang.Integer/parseInt % 2))
    (map #(char %))
    (reduce str)))



(defn encode-cmd
  "base64 encode a string"
  [{[_ s] :match}]
  (encode s))

(defn decode-cmd
  "base64 decode a string"
  [{[_ s] :match}]
  (decode s))

(cmd-hook #"base64"
          #"^encode\s(.+)" encode-cmd
          #"^decode\s(.+)" decode-cmd)