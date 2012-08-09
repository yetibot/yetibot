(ns yetibot.observers.chatoms
  (:use [yetibot.util :only (chat-result obs-hook)]
         [yetibot.util.http :only (get-json)]))

(def uri "http://chatoms.com/chatom.json?Normal=1&Fun=20&Philosophy=3&Out+There=4&Love=5&Personal=10")

(defn report-chatom
  []
  (chat-result
    (:text (get-json uri))))

(obs-hook
  ["KickMessage" "IdleMessage" "LeaveMessage" "TimestampMessage"
   "TopicChangeMessage"]
  (fn [event-json]
    (report-chatom)))
