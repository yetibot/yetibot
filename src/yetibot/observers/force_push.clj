(ns yetibot.observers.force-push
  (:require [yetibot.campfire :refer [chat-data-structure]]
            [yetibot.hooks :refer [obs-hook]]))

(def regex #"(?i)force.*push")

(defn report []
  (chat-data-structure
    (yetibot.handler/handle-unparsed-expr
      "image force push gif")))

(obs-hook
  ["TextMessage" "PasteMessage"]
  (fn [event-json]
    (if-let [m (re-find regex (:body event-json))]
      (report))))
