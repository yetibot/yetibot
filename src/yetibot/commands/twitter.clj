(ns yetibot.commands.twitter
  (:require [clojure.string :as s]
            [clojure.data.json :as json]
            [yetibot.campfire :as cf]
            [http.async.client :as ac])
  (:use [yetibot.util :only (cmd-hook ensure-config env)]
        [clojure.set :only (difference)]
        [twitter.oauth]
        [twitter.callbacks]
        [twitter.callbacks.handlers]
        [twitter.api.restful]
        [twitter.api.streaming])
  (:import
    (twitter.callbacks.protocols SyncSingleCallback)
    (twitter.callbacks.protocols AsyncStreamingCallback)))

(def config {:consumer_key (:TWITTER_CONSUMER_KEY env)
             :consumer_secret (:TWITTER_CONSUMER_SECRET env)
             :token (:TWITTER_TOKEN env)
             :secret (:TWITTER_SECRET env)})

(def ^:dynamic *creds* (make-oauth-creds
                         (:consumer_key config)
                         (:consumer_secret config)
                         (:token config)
                         (:secret config)))

(def streaming-callback
  (AsyncStreamingCallback. (comp (fn [text]
                                   (when-not (empty? text)
                                     (cf/chat-data-structure text)))
                                 #(:text %) json/read-json #(str %2))

                                 ;;; #(format "https://twitter.com/%s/status/%s" (:screen_name (:user %)) (:id %))
                           (comp println response-return-everything)
                           exception-print))

(def
  ^{:private true
    :doc "The set of topics tracking on the Twitter stream. When items are added
          or removed from this set, the streaming connection is automatically reset."}
  topics (atom #{}))

(def
  ^{:private true}
  streaming-response (atom nil))

(defn reset-streaming-topics [ts]
  (prn "stream" ts)
  ; first cacnel the streaming-response if it exists
  (when-let [s @streaming-response]
    ((:cancel (meta s))))
  ; now create a new streaming connection with the new topics
  (reset! streaming-response (statuses-filter :params {:track (s/join "," ts)}
                                              :oauth-creds *creds*
                                              :callbacks streaming-callback)))

(defn topic-watcher
  "Watch `topics` atom."
  [k r o n]
  (reset-streaming-topics n))

(add-watch topics :track topic-watcher)

;;; commands

(defn tracking
  "twitter tracking # show the topics that are being tracked on Twitter"
  [] @topics)

(defn track
  "twitter track <topic> # track a <topic> on the Twitter stream"
  [topic]
  (prn "track " topic)
  (if (@topics topic)
    (format "You're already tracking %s." topic)
    (do
      (swap! topics conj topic)
      (format "Now tracking %s" topic))))

(defn untrack
  "twitter untrack <topic> # stop tracking <topic>"
  [topic]
  (if (@topics topic)
    (do
      (swap! topics disj topic)
      (format "Untracked %s" topic))
    (format "You're not tracking %s" topic)))

(ensure-config
  (cmd-hook #"twitter"
            #"tracking" (tracking)
            #"untrack\s+(.+)" (untrack (nth p 1))
            #"track\s+(.+)" (track (nth p 1))
            ))
