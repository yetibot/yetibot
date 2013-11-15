(ns yetibot.models.twitter
  (:require
    [yetibot.config :refer [config-for-ns conf-valid?]]
    [clojure.string :refer [join]]
    [yetibot.chat :as chat]
    [clojure.data.json :as json]
    [twitter.oauth :refer :all]
    [twitter.callbacks :refer :all]
    [twitter.callbacks.handlers :refer :all]
    [twitter.api.restful :refer :all]
    [twitter.api.streaming :refer :all]
    [datomico.core :as dc]
    [datomico.db :refer [q]]
    [datomico.action :refer [all where raw-where]])
  (:import
    (twitter.callbacks.protocols SyncSingleCallback)
    (twitter.callbacks.protocols AsyncStreamingCallback)))

(def config (config-for-ns))
(def configured? (conf-valid?))

;;;; schema for storing topics to track

(def model-namespace :twitter)

(def schema (dc/build-schema model-namespace
                             [[:user :string]
                              [:topic :string]]))

(dc/create-model-fns model-namespace)

;;;; config

(def config {:consumer_key (:consumer-key config)
             :consumer_secret (:consumer-secret config)
             :token (:token config)
             :secret (:secret config)})

(def creds (apply make-oauth-creds
                  ((juxt :consumer_key :consumer_secret :token :secret) config)))

;;;; helper

(defn format-url [user id] (format "https://twitter.com/%s/status/%s" user id))

(defn send-tweet [json]
  (let [screen-name (:screen_name (:user json))
        url (format-url screen-name (:id json))]
    (prn "send tweet" (:text json))
    (chat/send-msg-to-all-adapters
      [(format "%s – @%s" (:text json) screen-name)
       url])))

;;;; streaming callback

(defn succ [x y]
  (try
    (let [raw (str y)
          json (if-not (empty? raw) (json/read-json raw))]
      (if (and json (:user json))
        (send-tweet json)))
    (catch Exception e)))

(def fail (comp println response-return-everything))

(def exception str)

(def streaming-callback (AsyncStreamingCallback. succ fail exception))

;;;; user stream

(defonce user-stream-resp
  (future (user-stream :oauth-creds creds :callbacks streaming-callback)))

;;;; topic tracking

(def statuses-streaming-response (atom nil))

(defn reset-streaming-topics [ts]
  ; first cancel the streaming-response if it exists
  (when-let [s @statuses-streaming-response] ((:cancel (meta s))))
  ; now create a new streaming connection with the new topics
  (reset! statuses-streaming-response
          (statuses-filter :params {:track (join "," ts)}
                           :oauth-creds creds
                           :callbacks streaming-callback)))

(defn reload-topics [] (reset-streaming-topics (map :topic (find-all))))

(defn add-topic [user-id topic]
  (create {:user user-id :topic topic})
  (reload-topics))

(defn remove-topic [topic-id]
  (dc/delete topic-id)
  (reload-topics))

;; on startup, load the existing topics
(future (reload-topics))

;;;; follow / unfollow

(defn follow [screen-name]
  (friendships-create :oauth-creds creds
                      :params {:screen_name screen-name}))

(defn unfollow [screen-name]
  (friendships-destroy :oauth-creds creds
                       :params {:screen_name screen-name}))

(defn following []
  (loop [cursor -1
         users []
         iter 0]
    (let [body (:body (friends-list :oauth-creds creds
                                    :params {:skip-status true
                                             :include-user-entities false
                                             :cursor cursor}))
          current-users (into users (:users body))
          next-cursor (:next_cursor body)]
      (if (or (> iter 10) (= 0 next-cursor)) ; limit to 10 pages
        current-users
        ; keep looping to fetch all pages until cursor is 0
        (recur next-cursor current-users (inc iter))))))

;;;; tweet

(defn tweet [status]
  (statuses-update :oauth-creds creds
                   :params {:status status}))

;;;; users

(defn user-timeline [screen-name]
  (statuses-user-timeline :oauth-creds creds
                          :params {:screen-name screen-name
                                   :count 3}))
