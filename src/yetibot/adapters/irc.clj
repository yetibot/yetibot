(ns yetibot.adapters.irc
  (:require
    [irclj.core :as irc]
    [clojure.string :refer [split-lines]]
    [yetibot.util :refer [env conf-valid?]]
    [yetibot.chat :refer [chat-data-structure send-msg-for-each]]
    [yetibot.util.format :as fmt]
    [yetibot.handler :refer [handle-unparsed-expr]]))

(def config (select-keys env [:IRC_HOST :IRC_PORT :IRC_USERNAME :IRC_CHANNELS]))

(declare conn)

(defn send-msg [msg]
  (irc/message conn (:IRC_CHANNELS env) msg))

(defn send-paste
  "In IRC there are new newlines. Each line must be sent as a separate message, so
   split it and send one for each"
  [p] (send-msg-for-each (split-lines p) send-msg))

(defn handle-message [_ info]
  (prn info)
  (if-let [[_ body] (re-find #"\!(.+)" (:text info))]
    (chat-data-structure
      (handle-unparsed-expr body)
      send-msg send-paste)))

(def callbacks {:privmsg handle-message})

; only try connecting when config is present
(defonce conn
  (when (conf-valid? config)
    (irc/connect (:IRC_HOST env) (read-string (:IRC_PORT env)) (:IRC_USERNAME env)
                 :callbacks callbacks)))

(defn start []
  (when conn
    (irc/join conn (:IRC_CHANNELS env))))
