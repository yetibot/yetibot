(ns yetibot.adapters.irc
  (:require
    [yetibot.chat]
    [irclj.core :as irc]
    [yetibot.models.users :as users]
    [clojure.string :refer [split-lines]]
    [yetibot.util :refer [env conf-valid? make-config]]
    [yetibot.chat :refer [chat-data-structure send-msg-for-each]]
    [yetibot.util.format :as fmt]
    [yetibot.handler :refer [handle-unparsed-expr]]))

(def config (make-config [:IRC_HOST :IRC_USERNAME :IRC_CHANNELS]))

(declare conn)

(def chat-source (format "irc/%s" (:IRC_CHANNELS env)))

(defn send-msg [msg]
  (irc/message conn (:IRC_CHANNELS env) msg))

(defn send-paste
  "In IRC there are new newlines. Each line must be sent as a separate message, so
   split it and send one for each"
  [p] (send-msg-for-each (split-lines p)))

(defn setup-users [users]
  (prn "setup users" users)
  (dorun (map (fn [[username user-info]]
                (users/add-user chat-source username user-info))
              users)))

(def messaging-fns
  {:msg send-msg
   :paste send-paste})

; todo lookup user by username
(defn handle-message [_ info]
  (let [nick (:nick info)
        user (users/get-user chat-source nick)]
    (prn "user is" user)
    (if-let [[_ body] (re-find #"\!(.+)" (:text info))]
      (binding [yetibot.chat/*messaging-fns* messaging-fns]
        (chat-data-structure
          (handle-unparsed-expr chat-source user body))))))

(defn raw-log [a b c]
  (prn "raw-log")
  (prn b c))

(defn end-of-names
  "Callback for end of names list from IRC"
  [irc event]
  (prn "end-of-names" irc)
  (let [users (-> @irc :channels vals first :users)]
    (setup-users users)))

(def callbacks {:privmsg handle-message
                :366 end-of-names})

; only try connecting when config is present
(defonce conn
  (when (conf-valid? config)
    (irc/connect (:IRC_HOST config) (read-string (or (:IRC_PORT env) "6667")) (:IRC_USERNAME config)
                 :callbacks callbacks)))

(defn start []
  (when conn
    (irc/join conn (:IRC_CHANNELS config))))

(defn part []
  (when conn
    (irc/part conn (:IRC_CHANNELS config))))
