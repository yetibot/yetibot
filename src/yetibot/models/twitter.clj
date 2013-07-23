(ns yetibot.models.twitter
  (:require
    [yetibot.util :refer [ensure-config env]]
    [clojure.string :refer [join]]
    [yetibot.campfire :as cf]
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

;;;; schema for storing topics to track

(def model-namespace :twitter)

(def schema (dc/build-schema model-namespace
                             [[:user-id :long]
                              [:topic :string]]))

(dc/create-model-fns model-namespace)

;;;; config

(def config {:consumer_key (:TWITTER_CONSUMER_KEY env)
             :consumer_secret (:TWITTER_CONSUMER_SECRET env)
             :token (:TWITTER_TOKEN env)
             :secret (:TWITTER_SECRET env)})

(def creds (apply make-oauth-creds
                  ((juxt :consumer_key :consumer_secret :token :secret) config)))

;;;; streaming callback

(defn format-url [user id] (format "https://twitter.com/%s/status/%s" user id))

(defn succ [x y]
  (try
    (let [raw (str y)
          json (if-not (empty? raw) (json/read-json raw))]
      (if (and json (:user json))
        (cf/send-tweet (format-url (:screen_name (:user json)) (:id json)))))
    (catch Exception e (prn "exception parsing twitter json" e (str x) (str y)))))

(def fail (comp println response-return-everything))

(def exception exception-print)

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
  (create {:user-id user-id :topic topic})
  (reload-topics))

(defn remove-topic [topic-id]
  (dc/delete topic-id)
  (reload-topics))

;; on startup, load the existing topics
(future (reload-topics))

;;;; follow



