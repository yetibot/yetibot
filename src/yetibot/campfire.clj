(ns yetibot.campfire
  (:require [http.async.client :as c]
            [clojure.data.json :as json]
            [clojure.contrib.string :as s]
            [clojure.stacktrace :as st]
            [yetibot.models.users :as users]
            [yetibot.util.http :as http]
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
  (cf/message cf-settings room (str msg)))

(defn send-paste [p]
  (cf/paste cf-settings room p))

(defn play-sound [sound]
  (cf/play-sound cf-settings room sound))

(defn send-message-for-each [msgs]
  (println (str "send" (count msgs) "messages"))
  (println msgs)
  (doseq [m msgs] (send-message m)))

(defn join-room []
  (with-open [client (c/create-client)]
    (let [uri (str base-uri "/room/" room-id "/join.json")
          resp (c/POST client uri :auth auth)]
      (println (str "joining at " uri))
      (c/await resp))))

(defn get-room []
  (let [uri (str base-uri "/room/" room-id ".json")
        users (http/get-json uri auth)]
    users))

; Monitor the chat room with the Streaming API
(defn listen-to-chat [message-callback]
  (with-open [client (c/create-client)]
    (join-room)
    (let [uri (str streaming-uri "/room/" room-id "/live.json")
              resp (c/stream-seq client :get uri :auth auth)]
      (println (str "Start listening on streaming API in room " room))
      (doseq [s (c/string resp)]
        (if (not (empty? (s/trim (str s))))
          ; Campfire sometimes returns multiple lines of json objects at a
          ; time, 1 per line so split the lines before parsing json
          (doseq [line (s/split #"\cM" s)]
            (future
              (try
                (let [json (json/read-json line)]
                  (println json)
                  (message-callback json))
                (catch Exception ex
                  (println (str "Exception in chat handler " ex))
                  (st/print-stack-trace (st/root-cause ex) 3)
                  (send-paste (str "An exception occurred: " ex)))))))))))

(defn start [message-callback]
  (def event-loop
    (future
      (while true
        (try
          (listen-to-chat message-callback)
          (catch Exception ex
            (println "Exception while listening to streaming api")
            (println ex)
            ))
        (println "Something bad happened. Sleeping for 2 seconds before reconnect")
        (. Thread (sleep 2000))))))
                           ; (message-callback (json/read-json s))
                           ;(let [json (json/read-json s)]
                           ;  (println json)
                           ;  (when (and (not (empty? (s/trim (str s))))
                           ;             (not= (str (:user_id json)) "1008539"))
                           ;    (println "pass it to the callback")
                           ;    (message-callback json)))))))))))

(defn chat-data-structure [d]
  "Formatters to send data structures to chat.
   If `d` is a nested data structure, it will attempt to recursively flatten
   or merge (if it's a map)."
  (if (and (not (map? d))
           (coll? d)
           (coll? (first d)))
    ; if it's a nested sequence, recursively flatten it
    (if (map? (first d))
      ; merge if the insides are maps
      (chat-data-structure (apply merge d))
      ; otherwise flatten
      (chat-data-structure (apply concat d)))
    ; otherwise send in the most appropriate manner
    (let [formatted (cond
                      (coll? d) (s/join \newline d)
                      :else (str d))]
      ; decide which of 3 ways to send to chat
      (cond
        ; send map as key: value pairs
        (map? d) (send-message-for-each
                   (map #(str (first %1) ": " (second %1)) d))
        ; send each item in the coll as a separate message
        (and
          (coll? d)
          (s/substring? (str \newline) formatted)
          (seq (filter #(s/substring? % formatted) ["jpg" "png" "gif"])))
        (send-message-for-each d)
        ; send the message with newlines as a paste
        (s/substring? (str \newline) formatted) (send-paste formatted)
        ; send as regular message
        :else (send-message formatted)))))
