(ns yetibot.models.users
  (:import java.util.Date)
  (:import java.sql.Timestamp)
  (:import java.text.SimpleDateFormat))

(def active-threshold-minutes 15)
(def active-threshold-milliseconds (* active-threshold-minutes 60 1000))
(def campfire-date-pattern "yyyy/MM/dd HH:mm:ss Z")
(def date-formatter (doto (new SimpleDateFormat campfire-date-pattern) (.setTimeZone (java.util.TimeZone/getTimeZone "GreenwichEtc"))))

(defonce users (atom {}))

;;; (defn add-user [id user]
;;;   (swap! user conj {id user}))

(defn get-refreshed-user
  "Returns an already existing user from the atom if available, otherwise a new user with a last_active timestamp"
  [user]
  (let [id (:id user)]
    (get @users id (assoc user :last_active (.format date-formatter (new Date))))))

(defn reset-users-from-room [room]
  (let [us (-> room :room :users)
        us-by-id (into {} (for [u us] [(:id u) (get-refreshed-user u)]))]
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

(defn get-user-ms [user] (.getTime (.parse date-formatter (:last_active user))))

(defn is-active?
  [user]
  (if (contains? user :last_active)
    (let [current-ms (.getTime (new Date))
          ms-since-active (- current-ms (get-user-ms user))]
      (< ms-since-active active-threshold-milliseconds))
    false))

(defn is-yetibot? [user] (= (str (:id user)) (System/getenv "CAMPFIRE_BOT_ID")))

(defn get-active-users [] (filter is-active? (vals @users)))

(defn get-active-humans [] (remove is-yetibot? (get-active-users)))

(defn get-updated-user [id last_active]
  (assoc (get-user id) :last_active last_active))

(defn update-active-timestamp [{id :user_id last_active :created_at}]
  (do
    (swap! users conj {id (get-updated-user id last_active)})
    (prn @users)))
