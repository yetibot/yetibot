(ns yetibot.commands.chatoms
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [get-json]]))

(def endpoint "http://chatoms.com/chatom.json?Normal=5&Fun=20&Philosophy=10&Out+There=4&Love=2&Personal=2")

(defn chat-cmd
  "chat # ask a question from chatoms.com"
  [_] (:text (get-json endpoint)))

(cmd-hook #"chat"
          _ chat-cmd)
