(ns yetibot.campfire
  (:require [http.async.client :as c]
            [clojure.data.json :as json]
            [clojure.string :as s]
            [clojure.stacktrace :as st]
            [yetibot.util :refer [make-config conf-valid?]]
            [yetibot.util.http :as http]
            [yetibot.util.format :as fmt]
            [clj-campfire.core :as cf]))

; Settings
(def config (make-config [:CAMPFIRE_API_KEY
                          :CAMPFIRE_ROOM_ID
                          :CAMPFIRE_SUBDOMAIN
                          :CAMPFIRE_ROOM]))

(def room-id (System/getenv "CAMPFIRE_ROOM_ID"))

(def cf-settings
  {:api-token (:CAMPFIRE_API_KEY config),
   :ssl true,
   :sub-domain (:CAMPFIRE_SUBDOMAIN config)})
(def room (:CAMPFIRE_ROOM config))
(def json-headers {:content-type "application/json"})
(def escapees {\" "\\\""})

(def base-uri (str "https://" (:sub-domain cf-settings) ".campfirenow.com"))
(def streaming-uri "https://streaming.campfirenow.com")
(def auth {:user (:CAMPFIRE_API_KEY config) :password "x" :preemptive true})

(defn send-message [msg]
  (let [msg (if (s/blank? msg) "No results" msg)]
    (cf/message cf-settings room (str msg))))

(defn send-paste [p] (cf/paste cf-settings room p))

(defn send-tweet [t] (cf/tweet cf-settings room t))

(defn self [token]
  (let [auth {:user token :password "x"}
        uri (str base-uri "/users/me.json")]
    (http/get-json uri auth)))

(defn play-sound [sound] (cf/play-sound cf-settings room sound))

(defn send-message-for-each [msgs] (doseq [m msgs] (send-message m)))

(defn join-room []
  (with-open [client (c/create-client)]
    (let [uri (str base-uri "/room/" room-id "/join.json")
          resp (c/POST client uri :auth auth)]
      (println (str "✓ Joining room at " uri))
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
              resp (c/stream-seq client :get uri :auth auth :timeout -1)]
      (println (str "✓ Start listening on streaming API in room " room))
      (doseq [s (c/string resp)]
        (if (not (empty? (s/trim (str s))))
          ; Campfire sometimes returns multiple lines of json objects at a
          ; time, 1 per line so split the lines before parsing json
          (doseq [line (s/split s #"\cM")]
            (future
              (try
                (let [json (json/read-json line)]
                  (println json)
                  (message-callback json))
                (catch Exception ex
                  (println (str "Exception in chat handler " ex))
                  (st/print-stack-trace (st/root-cause ex) 12))))))))))

(defn start [message-callback]
  (if (conf-valid? config)
    (future
      (while true
        (try
          (listen-to-chat message-callback)
          (catch Exception ex
            (println "Exception while listening to streaming api")
            (println ex)))
        (println "Something bad happened. Sleeping for 2 seconds before reconnect")
        (. Thread (sleep 2000))))
    (println "✗ Campfire is not configured")))

(defn contains-image-url-lines?
  "Returns true if the string contains an image url on its own line, separated from
   other characters by a newline"
  [string]
  (not (empty? (filter #(re-find (re-pattern (str "(?m)^http.*\\." %)) string) ["jpeg" "jpg" "png" "gif"]))))

(defn should-send-msg-for-each?
  [d formatted]
  (and
    (coll? d)
    (<= (count d) 30)
    (re-find #"\n" formatted)
    (contains-image-url-lines? formatted)))

(defn chat-data-structure [d]
  "Formatters to send data structures to chat.
  If `d` is a nested data structure, it will attempt to recursively flatten
  or merge (if it's a map)."
  (when-not (:suppress (meta d))
    (let [[formatted flattened] (fmt/format-data-structure d)]
      (cond
        ; send each item in the coll as a separate message if it contains images and
        ; the total length of the collection is less than 20
        (should-send-msg-for-each? d formatted) (send-message-for-each flattened)
        ; send the message with newlines as a paste
        (re-find #"\n" formatted) (send-paste formatted)
        ; send as regular message
        :else (send-message formatted)))))
