(ns yetibot.models.users)

(defonce users (atom {}))

;;; (defn add-user [id user]
;;;   (swap! user conj {id user}))

(defn reset-users-from-room [room]
  (let [us (-> room :room :users)
        us-by-id (into {} (for [u us] [(:id u) u]))]
    (reset! users us-by-id)))

(defn get-user [id]
  (get @users id))

(defn get-user-by-name [name]
  (let [us (filter #(= name (:name %)) (vals @users))]
    (if us (first us) nil)))

(defn get-user-names []
  (map :name (vals @users)))

(defn get-rand-user []
  (rand-nth (vals @users)))
