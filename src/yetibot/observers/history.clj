(ns yetibot.observers.history
  (:require [yetibot.models.history :as h])
  (:use [yetibot.util :only (obs-hook)]))

(obs-hook
  ["TextMessage" "PasteMessage"]
  (fn [event-json]
    (h/add event-json)))
