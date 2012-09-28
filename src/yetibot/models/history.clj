(ns yetibot.models.history
  (:require [yetibot.models.users :as u]))

(defonce history (atom []))

(defn items-with-user []
  (for [i @history]
    {:user (u/get-user (:user_id i)) :body (:body i)}))

(defn fmt-items-with-user []
  (for [m (items-with-user)]
    (str (-> m :user :name) ": " (:body m))))

(defn add [json]
  (swap! history conj json))
