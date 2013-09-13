(ns yetibot.models.users
  (:require
    [clj-time.core :refer [now]]))

(def config {:active-threshold-milliseconds (* 15 60 1000)})

;; keys: username, id, last-active
(defonce ^{:private true} users (atom {}))

(defn add-user
  "Add a user according to source. Source may be string identifying a Campfire room
   or IRC channel"
  [chat-source username {:keys [id] :as user-info}]
  (let [id (str (or id username))] ; use username as the id if nil
    (swap! users assoc-in [chat-source id]
           (merge user-info {:username username
                             :name username ; backward compat
                             :id id
                             :last-active (now)}))))

(defn get-users [source]
  (@users source))

(defn get-user [source id]
  ((get-users source) (str id)))

; (def campfire-date-pattern "yyyy/MM/dd HH:mm:ss Z")
; (def date-formatter (doto (new SimpleDateFormat campfire-date-pattern) (.setTimeZone (java.util.TimeZone/getTimeZone "GreenwichEtc"))))

;;; (defn add-user [id user]
;;;   (swap! user conj {id user}))

(defn get-refreshed-user
  "Returns an already existing user from the atom if available, otherwise a new user with a last_active timestamp"
  [user]
  (let [id (:id user)]
    ))
    ; (get @users id (assoc user :last_active (.format date-formatter (new Date))))))

(defn get-user-by-name [name]
  (let [us (filter #(= name (:name %)) (vals @users))]
    (if us (first us) nil)))

(defn get-user-names []
  (map :name (vals @users)))

(defn find-user-like [name]
  (let [patt (re-pattern (str "(?i)" name))]
    (some #(when (re-find patt (:name %)) %) (vals @users))))

(defn get-rand-user []
  (rand-nth (vals @users)))

; (defn get-user-ms [user] (.getTime (.parse date-formatter (:last_active user))))

(defn is-active?
  [user]
  false)

  ; (if (contains? user :last_active)
  ;   (let [current-ms (.getTime (new Date))
  ;         ms-since-active (- current-ms (get-user-ms user))]
  ;     (< ms-since-active active-threshold-milliseconds))
  ;   false))

(defn is-yetibot? [user] (= (str (:id user)) (System/getenv "CAMPFIRE_BOT_ID")))

(defn get-active-users [] (filter is-active? (vals @users)))

(defn get-active-humans [] (remove is-yetibot? (get-active-users)))

(defn get-updated-user [id last_active]
  (assoc (get-user id) :last_active last_active))

(defn update-active-timestamp [{id :user_id last_active :created_at}]
  (do
    (swap! users conj {id (get-updated-user id last_active)})
    (prn @users)))
