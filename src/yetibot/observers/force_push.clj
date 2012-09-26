(ns yetibot.observers.force-push
  (:use [yetibot.util :only (obs-hook)]
        [yetibot.campfire :only (chat-data-structure)]))

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
