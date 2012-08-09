(ns yetibot.observers.chatoms
  (:use [yetibot.util :only (chat-result obs-hook)]
        [useful.fn :only (rate-limited)]
        [yetibot.util.http :only (get-json)]))

(def uri "http://chatoms.com/chatom.json?Normal=1&Fun=20&Philosophy=3&Out+There=4&Love=5&Personal=10")

(def five-minutes 300000)

(def report-chatom
  (rate-limited
    #((chat-result (:text (get-json uri))))
    five-minutes))

(obs-hook
  ["KickMessage" "IdleMessage" "LeaveMessage" "TimestampMessage"
   "TopicChangeMessage"]
  (fn [event-json]
    (report-chatom)))
