(ns yetibot.observers.chatoms
  (:require [yetibot.models.users :as users]
            [yetibot.campfire :refer [chat-data-structure]]
            [yetibot.hooks :refer [obs-hook]]
            [useful.fn :refer [rate-limited]]
            [yetibot.util.http :refer [get-json]]))

(def uri "http://chatoms.com/chatom.json?Normal=1&Fun=20&Philosophy=3&Out+There=4&Love=5&Personal=10")

(def five-minutes 300000)
(def two-hours 7200000)

(defn user-prefix
  []
  (if-let [u (users/get-rand-user)]
    (str (:name u) ": ")))

(def report-chatom
  (rate-limited
    #((chat-data-structure
        (str (user-prefix) (:text (get-json uri)))))
    two-hours))

(obs-hook
  ["KickMessage" "IdleMessage" "LeaveMessage" "TopicChangeMessage"]
  (fn [event-json]
    (report-chatom)))
