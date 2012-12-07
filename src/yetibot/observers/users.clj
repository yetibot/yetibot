(ns yetibot.observers.users
  (:require [yetibot.models.users :as users]
            [yetibot.campfire :as cf])
  (:use [yetibot.hooks :only (obs-hook)]
        [yetibot.campfire :only (chat-data-structure)]))

(obs-hook
  ["KickMessage" "LeaveMessage" "EnterMessage"]
  (fn [event-json]
    (users/reset-users)))

(obs-hook
  ["TextMessage" "PasteMessage"]
  (fn [event-json]
    (users/update-active-timestamp event-json)))
