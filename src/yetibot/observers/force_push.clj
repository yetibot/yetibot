(ns yetibot.observers.force-push
  (:require [yetibot.campfire :refer [chat-data-structure]]
            [yetibot.hooks :refer [obs-hook]]))

(def regex #"(?i)force.*push")

(defn report []
  (chat-data-structure
    (yetibot.core/parse-and-handle-command
      "image force push" nil nil)))

(obs-hook
  ["TextMessage" "PasteMessage"]
  (fn [event-json]
    (if-let [m (re-find regex (:body event-json))]
      (report))))
