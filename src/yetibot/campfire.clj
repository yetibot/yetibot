(ns yetibot.campfire
  (:require [http.async.client :as c]
            [clojure.data.json :as json]
            [clojure.contrib.string :as s]
            [clj-campfire.core :as cf])
  (:use [clojure.tools.logging :only (info error)]))


; Settings
(def u (System/getenv "CAMPFIRE_API_KEY"))
(def p "x")
(def room-id (System/getenv "CAMPFIRE_ROOM_ID"))

(def cf-settings
  {:api-token u,
   :ssl true,
   :sub-domain (System/getenv "CAMPFIRE_SUBDOMAIN")})
(def room (System/getenv "CAMPFIRE_ROOM"))
(def json-headers {:content-type "application/json"})
(def escapees {\" "\\\""})


(def base-uri (str "https://" (:sub-domain cf-settings) ".campfirenow.com"))
(def streaming-uri "https://streaming.campfirenow.com")
(def auth {:user u :password p :preemptive true})


(defn send-message [msg]
  (cf/message cf-settings room msg))

(defn send-paste [p]
  (cf/paste cf-settings room p))

(defn join-room []
  (with-open [client (c/create-client)]
    (let [uri (str base-uri "/room/" room-id "/join.json")
          resp (c/POST client uri :auth auth)]
      (println (str "joining at " uri))
      (c/await resp))))

; Monitor the chat room with the Streaming API
(defn listen-to-chat [message-callback]
  (with-open [client (c/create-client)]
    (join-room)
    (let [uri (str streaming-uri "/room/" room-id "/live.json")
              resp (c/stream-seq client :get uri :auth auth)]
      (println "Start listening on streaming API")
      (doseq [s (c/string resp)]
        (if (not (empty? (s/trim (str s))))
          ; Campfire sometimes returns multiple lines of json objects at a
          ; time, 1 per line so split the lines before parsing json
          (doseq [line (s/split #"\cM" s)]
            (try
              (let [json (json/read-json line)]
                (println json)
                (message-callback json))
              (catch Exception ex
                (println (str "Exception parsing json" ex))))))))))

(defn start [message-callback]
  (def event-loop
    ;;; (future-call (bound-fn [] ; call on a separate thread so it doesn't block
                   (while true
                     (try
                       (listen-to-chat message-callback)
                       (catch Exception ex
                         (println "Exception while listening to streaming api")
                         (println ex)
                         ))
                     (println "Something bad happened. Sleeping for 2 seconds before reconnect")
                     (. Thread (sleep 2000)))))
    ; ))


                           ; (message-callback (json/read-json s))
                           ;(let [json (json/read-json s)]
                           ;  (println json)
                           ;  (when (and (not (empty? (s/trim (str s))))
                           ;             (not= (str (:user_id json)) "1008539"))
                           ;    (println "pass it to the callback")
                           ;    (message-callback json)))))))))))

