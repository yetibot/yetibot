(ns yetibot.observers.users
  (:require [yetibot.models.users :as users]
            [yetibot.adapters.campfire :as cf])
  (:use [yetibot.hooks :only (obs-hook)]
        [yetibot.chat :only (chat-data-structure)]))

(obs-hook
  ["KickMessage" "LeaveMessage" "EnterMessage"]
  (fn [event-json]
    ; TODO
    ; (users/reset-users)
    ))

(obs-hook
  ["TextMessage" "PasteMessage"]
  (fn [event-json]
    (users/update-active-timestamp event-json)))
