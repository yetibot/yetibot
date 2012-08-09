(ns yetibot.observers.users
  (:require [yetibot.models.users :as users]
            [yetibot.campfire :as cf])
  (:use [yetibot.util :only (chat-result obs-hook)]))

(def refresh-future (atom nil))

(defn refresh-users []
  (prn "refreshing users list")
  (when (future? @refresh-future)
    (prn "cancel existing refresh future")
    (future-cancel @refresh-future))
  (reset! refresh-future (future
                           (users/reset-users-from-room
                             (cf/get-room)))))

(obs-hook
  ["KickMessage" "LeaveMessage" "EnterMessage"]
  (fn [event-json]
    (refresh-users)))
