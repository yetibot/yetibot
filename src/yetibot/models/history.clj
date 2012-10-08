(ns yetibot.models.history
  (:require [yetibot.models.users :as u]))

(defonce history (atom []))

(defn items-with-user []
  "Retrieve a map of user to chat body"
  (for [i @history]
    {:user (u/get-user (:user_id i)) :body (:body i)}))

(defn fmt-items-with-user []
  "Format map of user to chat body as a string"
  (for [m (items-with-user)]
    (str (-> m :user :name) ": " (:body m))))

(defn items-for-user [{:keys [id]}]
  (filter #(= (:user_id %) id) @history))


(defn add [json]
  (swap! history conj json))
