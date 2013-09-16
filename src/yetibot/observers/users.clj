(ns yetibot.observers.users
  (:require
    [yetibot.models.users :as users]
    [yetibot.adapters.campfire :as cf]
    [yetibot.hooks :refer [obs-hook]]
    [yetibot.chat :refer [chat-data-structure]]))

(obs-hook
  #{:enter}
  (fn [event-info]
    (users/add-user (:chat-source event-info)
                    (:user event-info))))

(obs-hook
  #{:leave}
  (fn [event-info]
    (users/remove-user (:chat-source event-info)
                       (-> event-info :user :id))))

; (obs-hook
;   ["KickMessage" "LeaveMessage" "EnterMessage"]
;   (fn [event-json]
;     ; TODO
;     ; (users/reset-users)
;     ))

; (obs-hook
;   ["TextMessage" "PasteMessage"]
;   (fn [event-json]
;     (users/update-active-timestamp event-json)))
