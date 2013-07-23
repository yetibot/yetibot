(ns yetibot.models.twitter
  (:require
    [yetibot.util :refer [ensure-config env]]
    [clojure.string :as s]
    [yetibot.campfire :as cf]
    [clojure.data.json :as json]
    [twitter.oauth :refer :all]
    [twitter.callbacks :refer :all]
    [twitter.callbacks.handlers :refer :all]
    [twitter.api.restful :refer :all]
    [twitter.api.streaming :refer :all])
  (:import
    (twitter.callbacks.protocols SyncSingleCallback)
    (twitter.callbacks.protocols AsyncStreamingCallback)))

(def config {:consumer_key (:TWITTER_CONSUMER_KEY env)
             :consumer_secret (:TWITTER_CONSUMER_SECRET env)
             :token (:TWITTER_TOKEN env)
             :secret (:TWITTER_SECRET env)})

(def creds (apply make-oauth-creds
                  ((juxt :consumer_key :consumer_secret :token :secret) config)))

;; streaming callback

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

;; user stream

(defonce user-stream-resp
  (future (user-stream :oauth-creds creds :callbacks streaming-callback)))

;; topic tracking

(def
  ^{:private true
    :doc "The set of topics tracking on the Twitter stream. When items are added
          or removed from this set, the streaming connection is automatically reset."}
  topics (atom #{}))

(def streaming-response (atom nil))

(defn reset-streaming-topics [ts]
  (prn "stream" ts)
  ; first cacnel the streaming-response if it exists
  (when-let [s @streaming-response]
    ((:cancel (meta s))))
  ; now create a new streaming connection with the new topics
  (reset! streaming-response (statuses-filter :params {:track (s/join "," ts)}
                                              :oauth-creds creds
                                              :callbacks streaming-callback)))

(defn topic-watcher
  "Watch `topics` atom."
  [k r o n]
  (reset-streaming-topics n))

(add-watch topics :track topic-watcher)

;; follow



