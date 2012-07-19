(ns yetibot.observers.twss
  (:require [robert.hooke :as rh]
            [yetibot.campfire :as cf]
            [clojure.contrib.string :as s]
            [useful.fn :as useful]
            [yetibot.core :as core]
            [clojure.xml :as xml])
  (:use [yetibot.util]))

; todo - abstract into observe-hook macro

; observers hook handle-text-message which is called before
; the more specific handle-command

(def endpoint "http://twss-classifier.heroku.com/?sentence=")
(def false-phrase "Not TWSS")
(def reply "That's what she said")

(defn load-twss [phrase]
  (xml/parse (str endpoint (encode phrase))))

(defn parse-result-from-twss [xml]
  (let [xs (xml-seq xml)
        result (first (for [el xs :when (= (:id (:attrs el)) "result")] el))]
    (if (s/substring? false-phrase (str result))
      false
      (cf/send-message (str reply)))))

(defn observe [event-type body]
  (println (str "observing " event-type body))
  (parse-result-from-twss
    (load-twss body)))

(rh/add-hook
  #'core/handle-text-message
  (fn [callback json]
    (core/parse-event json
                      (try
                        (observe event-type body)
                        (catch Exception e
                          (println (str "observer exception" e)))))
    ; finish up by passing it back to handle-text-message
    (callback json)))
