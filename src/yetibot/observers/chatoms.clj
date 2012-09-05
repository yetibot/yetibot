(ns yetibot.observers.chatoms
  (:require [yetibot.models.users :as users])
  (:use [yetibot.util :only (obs-hook)]
        [yetibot.campfire :only (chat-data-structure)]
        [useful.fn :only (rate-limited)]
        [yetibot.util.http :only (get-json)]))

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
