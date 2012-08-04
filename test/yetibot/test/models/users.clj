(ns yetibot.models.users)

(def users (atom {}))

(defn add-user [id user]
  (swap! user conj {id user}))

(defn get-user [id]
  (get @users id))
