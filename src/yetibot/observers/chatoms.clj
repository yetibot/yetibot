(ns yetibot.observers.chatoms
  (:require [yetibot.models.users :as users])
  (:use [yetibot.util :only (chat-result obs-hook)]
        [useful.fn :only (rate-limited)]
        [yetibot.util.http :only (get-json)]))

(def uri "http://chatoms.com/chatom.json?Normal=1&Fun=20&Philosophy=3&Out+There=4&Love=5&Personal=10")

(def five-minutes 300000)

(defn user-prefix
  []
  (if-let [u (users/get-rand-user)]
    (str (:name u) ": ")))

(def report-chatom
  (rate-limited
    #((chat-result
        (str (user-prefix) (:text (get-json uri)))))
    five-minutes))

(obs-hook
  ["KickMessage" "IdleMessage" "LeaveMessage" "TimestampMessage"
   "TopicChangeMessage"]
  (fn [event-json]
    (report-chatom)))
