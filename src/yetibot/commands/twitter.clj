(ns yetibot.commands.twitter
  (:require [clojure.string :as s]
            [clojure.data.json :as json]
            [yetibot.campfire :as cf]
            [http.async.client :as ac])
  (:use [yetibot.util :only (cmd-hook ensure-config env)]
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
  (AsyncStreamingCallback. (comp cf/chat-data-structure #(:text %) json/read-json #(str %2))
                           (comp println response-return-everything)
                           exception-print))

(def ^:private topics (atom []))

(add-watch topics :track (fn [k r o n]
                           (prn "begin tracking " n)
                           (statuses-filter :params {:track n}
                                            :oauth-creds *creds*
                                            :callbacks streaming-callback)))

(defn track
  "track <topic> # track a <topic> on the Twitter stream"
  [topic]
  (prn "track " topic)
  (swap! topics conj topic)
  (format "Now tracking %s" topic))

(ensure-config
  (cmd-hook #"twitter"
            #"track\s+(.+)" (track (nth p 1))
            ))
