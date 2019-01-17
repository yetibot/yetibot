(ns yetibot.observers.force-push
  (:require
    [taoensso.timbre :refer [warn info]]
    [yetibot.core.chat :refer [chat-data-structure]]
    [yetibot.core.hooks :refer [obs-hook]]))

(def regex #"(?i)force.*push")

(defn report []
  (info "post force push")
  (chat-data-structure
    (:value (yetibot.core.handler/handle-unparsed-expr
              "image force push gif"))))

(obs-hook
  #{:message}
  (fn [event-info]
    (if-let [m (re-find regex (:body event-info))]
      (report))))
