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
    [yetibot.config :refer [get-config config-for-ns conf-valid?]]
    [yetibot.chat :refer [send-msg-for-each register-chat-adapter]]
    [yetibot.util.format :as fmt]
    [yetibot.handler :refer [handle-raw]]))

(def conn (atom nil))
(declare config channel connect start)
(def chat-source (format "irc/%s" channel))
(def wait-before-reconnect 15000)

(def send-msg
  "Rate-limited function for sending messages to IRC. It's rate limited in order
   to prevent 'Excess Flood' kicks"
  (rate-limit
    (fn [msg]
      (try
        (irc/message @conn channel msg)
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

(def prepare-paste
  "Since pastes are sent as individual messages, blank lines would get
   translated into \"No Results\" by the chat namespace. Instead of a blank
   line, map it into a single space."
  (comp (fn [coll] (map #(if (empty? %) " " %) coll))
        split-lines))

(defn send-paste
  "In IRC there are new newlines. Each line must be sent as a separate message, so
   split it and send one for each"
  [p] (send-msg-for-each (prepare-paste p)))

(defn fetch-users []
  (irc-conn/write-irc-line @conn "WHO" channel))

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

(defn raw-log [a b c] #_(debug b c))

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
      (:host config) (read-string (or (:port config) "6667")) (:username config)
      :callbacks callbacks)))

(defn start
  "Join and fetch all users with WHO <channel>"
  []
  (def config (get-config :yetibot :adapters :irc))
  (def channel (first (:channels config)))
  (when (conf-valid? config)
    (register-chat-adapter 'yetibot.adapters.irc)
    (connect)
    (irc/join @conn channel)
    (fetch-users)))

(defn part []
  (when conn
    (irc/part @conn channel)))
