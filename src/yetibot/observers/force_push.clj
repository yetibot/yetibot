(ns yetibot.observers.force-push
  (:require
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.hooks :refer [obs-hook]]))

(def regex #"(?i)force.*push")

(defn report []
  (chat-data-structure
    (yetibot.core.handler/handle-unparsed-expr
      "image force push gif")))

(obs-hook
  #{:message}
  (fn [event-info]
    (if-let [m (re-find regex (:body event-info))]
      (report))))
