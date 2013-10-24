(ns yetibot.adapters.irc
  (:require
    [rate-gate.core :refer [rate-limit]]
    [taoensso.timbre :refer [info warn error debug]]
    [yetibot.chat]
    [irclj
     [core :as irc]
     [connection :as irc-conn]]
    [yetibot.models.users :as users]
    [clojure.string :refer [split-lines]]
    [yetibot.util :refer [env conf-valid? make-config]]
    [yetibot.chat :refer [send-msg-for-each]]
    [yetibot.util.format :as fmt]
    [yetibot.handler :refer [handle-raw]]))

(def config (make-config [:IRC_HOST :IRC_USERNAME :IRC_CHANNELS]))
(def conn (atom nil))
(declare connect start)
(def chat-source (format "irc/%s" (:IRC_CHANNELS env)))
(def wait-before-reconnect 15000)

(def send-msg
  (rate-limit
    (fn [msg]
      (info "conn is" conn "send message" msg)
      (try
        (irc/message @conn (:IRC_CHANNELS env) msg)
        (catch java.net.SocketException e
          ; it must have disconnect, try reconnecting again
          (info "SocketException, trying to reconnect in" wait-before-reconnect "ms")
          (Thread/sleep wait-before-reconnect)
          (connect)
          (start))))
    3 600))

(defn- create-user [info]
  (let [username (:nick info)
        id (:user info)]
    (users/create-user username (merge info {:id id}))))

(defn send-paste
  "In IRC there are new newlines. Each line must be sent as a separate message, so
   split it and send one for each"
  [p] (send-msg-for-each (split-lines p)))

(defn fetch-users []
  (irc-conn/write-irc-line @conn "WHO" (:IRC_CHANNELS env)))

(def messaging-fns
  {:msg send-msg
   :paste send-paste})

(defn handle-message [_ info]
  (let [user-id (:user info)
        user (users/get-user chat-source user-id)]
    (binding [yetibot.chat/*messaging-fns* messaging-fns]
      (handle-raw chat-source user :message (:text info)))))

(defn handle-part [_ info]
  (handle-raw chat-source
              (create-user info) :leave nil))

(defn handle-join [_ info]
  (handle-raw chat-source
              (create-user info) :enter nil))

(defn handle-nick [_ info]
  (let [[nick] (:params info)
        id (:user info)]
    (users/update-user chat-source id {:username nick :name nick})))

(defn handle-who-reply [_ event-info]
  (debug "352" event-info)
  (let [{[_ _ user _ _ nick] :params} event-info]
    (info "add user" user nick)
    (users/add-user chat-source
                    (create-user {:user user :nick nick}))))

(defn raw-log [a b c] (debug "raw" b c))

(defn handle-end-of-names
  "Callback for end of names list from IRC. Currently not doing anything with it."
  [irc event]
  (let [users (-> @irc :channels vals first :users)]))

(def callbacks {:privmsg #'handle-message
                :raw-log #'raw-log
                :part #'handle-part
                :join #'handle-join
                :nick #'handle-nick
                :366 #'handle-end-of-names
                :352 #'handle-who-reply})

(defn connect []
  (reset!
    conn
    (irc/connect
      (:IRC_HOST config) (read-string (or (:IRC_PORT env) "6667")) (:IRC_USERNAME config)
      :callbacks callbacks)))

; only try connecting when config is present

(defonce initial-conn
  (when (conf-valid? config)
    (connect)))

(defn start
  "Join and fetch all users with WHO <channel>"
  []
  (when conn
    (irc/join @conn (:IRC_CHANNELS config))
    (fetch-users)))

(defn part []
  (when conn
    (irc/part @conn (:IRC_CHANNELS config))))
