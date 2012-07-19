(ns yetibot.util
  (:require [http.async.client :as client]
            [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [yetibot.campfire :as cf]
            [yetibot.help :as help]
            [robert.hooke :as rh]
            [clojure.data.json :as json])
  (:use [clojure.contrib.cond])
  (:import (java.net URL URLEncoder)))

; synchronous api call helpers
(defmacro with-client [uri verb-fn auth & body]
  `(with-open [~'client (client/create-client)]
     (let [~'response (~verb-fn ~'client ~uri :auth ~auth)]
       ~@body)))

(defn get-json
  ([uri] (get-json uri {:user "" :password ""}))
  ([uri auth] (get-json uri client/GET auth))
  ([uri verb-fn auth]
   (with-client uri verb-fn auth
                (client/await response)
                (json/read-json (client/string response)))))


; formatters to send data structures to chat
(defn chat-result [d]
  (let [formatted (cond
                    (coll? d) (s/join \newline d)
                    :else (str d))]
    ; decide which of 3 ways to send to chat
    (cond
      ; send each item in the coll as a separate message
      (and
        (coll? d)
        (s/substring? (str \newline) formatted)
        (filter #(s/substring? % formatted) ["jpg" "png" "gif"]))
      (cf/send-message-for-each d)
      ; send the message with newlines as a paste
      (s/substring? (str \newline) formatted) (cf/send-paste formatted)
      ; send as regular message
      :else (cf/send-message formatted))))


; command hook
(defmacro cmd-hook [prefix & exprs]
  `(do
     (rh/add-hook
       #'core/handle-command
       (fn [~'callback ~'cmd ~'args]
         (if (re-find ~prefix (s/lower-case ~'cmd))
           (do
             (println (str "found " ~prefix ". args are:" ~'args))
             ; try matching the available sub-commands
             (cond-let [~'p]
                       ~@(map (fn [i#]
                                (if (instance? java.util.regex.Pattern i#)
                                  `(re-find ~i# ~'args) ; prefix to match
                                  `(chat-result ~i#))) ; chat results of handler
                              exprs)))
           (~'callback ~'cmd ~'args))))
     ; extract the meta from the commands and use it to build docs
     (help/add-docs ~prefix
                    (map
                      (fn [i#]
                        (if (list? i#)
                          (:doc (meta (resolve (first i#))))))
                      '~exprs))))



(defn encode [s]
  (URLEncoder/encode (str s) "UTF-8"))

; query string helper
(defn map-to-query-string [m]
  (s/join "&" (map (fn [[k v]] (format "%s=%s"
                                       (encode (name k)) (encode v))) m)))
