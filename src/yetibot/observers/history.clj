(ns yetibot.observers.history
  (:require [yetibot.models.history :as h]
            [yetibot.hooks :refer [obs-hook]]))

(obs-hook
  ["TextMessage" "PasteMessage"]
  (fn [event-json]
    (h/add event-json)))
