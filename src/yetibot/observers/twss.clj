(ns yetibot.observers.twss
  (:require [robert.hooke :as rh]
            [yetibot.campfire :as cf]
            [clojure.contrib.string :as s]
            [useful.fn :as useful :only rate-limited]
            [yetibot.core :as core]
            [clojure.xml :as xml])
  (:use [yetibot.util]))

; todo - abstract into observe-hook macro

; observers hook handle-text-message which is called before
; the more specific handle-command

(def one-hour 3600000)
(def endpoint "http://twss-classifier.heroku.com/?sentence=")
(def false-phrase "Not TWSS")
(def reply "That's what she said")

(def reply-once-per-hour
  (useful/rate-limited
    #((cf/send-message (str reply))) one-hour))

(defn load-twss [phrase]
  (xml/parse (str endpoint (encode phrase))))

(defn parse-result-from-twss [xml]
  (let [xs (xml-seq xml)
        result (first (for [el xs :when (= (:id (:attrs el)) "result")] el))]
    (if (s/substring? false-phrase (str result))
      false
      (reply-once-per-hour))))

(obs-hook
  ["TextMessage" "PasteMessage"]
  (fn [event-json]
    (parse-result-from-twss
      (load-twss (:body event-json)))))
