(ns yetibot.models.users)

(def users (atom {}))

(defn add-user [id user]
  (swap! user conj {id user}))

(defn reset-users-from-room [room]
  (let [us (-> room :room :users)
        us-by-id (into {} (for [u us] [(:id u) u]))]
    (reset! users us-by-id)))

(defn get-user [id]
  (get @users id))
