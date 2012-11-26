(ns yetibot.commands.giftv
  (:require [clojure.string :as s])
  (:use [yetibot.hooks :only (cmd-hook)]
        [yetibot.util.http :only (fetch)]))

(def endpoint "http://www.gif.tv/gifs/get.php")

(defn gif-uri [gif]
  (format "http://www.gif.tv/gifs/%s.gif" gif))

(defn giftv-cmd
  "giftv # fetch a random gif from gif.tv"
  [_]
  (-> (fetch endpoint)
    gif-uri))


(cmd-hook #"giftv"
          _ giftv-cmd)
