(ns yetibot.models.history
  (:require [yetibot.models.users :as u]))

(defonce history (atom []))

(defn items-with-user []
  (for [i @history]
    [(:name (u/get-user (:user_id i))) (:body i)]))

(defn add [json]
  (println "add event to history" json)
  (swap! history conj json)
  (println "history is now" @history)
  (println (items-with-user)))
