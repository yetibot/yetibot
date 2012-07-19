(ns yetibot.observers.twss
  (:require [clojure.string :as s]
            [robert.hooke :as rh]
            [yetibot.core :as core]
            [clojure.xml :as xml])
  (:use [yetibot.util]))

; todo - abstract into observe-hook macro

; observers hook handle-text-message which is called before
; the more specific handle-command
(rh/add-hook
  #'core/handle-text-message
  (fn [callback json]
    (core/parse-event json
                 (println (str "observing " event-type body)))

    ; finish up by psasing it back to handle-text-message
    (callback json)))
