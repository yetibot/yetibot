(ns yetibot.observers.users
  (:require
    [yetibot.core.models.users :as users]
    [yetibot.core.adapters.campfire :as cf]
    [yetibot.core.hooks :refer [obs-hook]]
    [yetibot.core.chat :refer [chat-data-structure]]))

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
