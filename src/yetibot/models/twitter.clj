(ns yetibot.models.twitter
  (:require
    [schema.core :as sch]
    [taoensso.timbre :refer [info warn error]]
    [clj-http.client :as client]
    [yetibot.core.util.http :refer [html-decode]]
    [yetibot.core.config :refer [get-config]]
    [clojure.string :as s :refer [join]]
    [yetibot.core.chat :as chat]
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
                             [[:user :string]
                              [:topic :string]]))

(dc/create-model-fns model-namespace)

;;;; config

(def twitter-schema
  {:consumer {:key sch/Str
              :secret sch/Str}
   :token sch/Str
   :secret sch/Str
   :search {:lang sch/Str}})

(def config (:value (get-config twitter-schema [:yetibot :twitter])))

{:search {:lang "en"},
 :consumer {:secret "Yf8TrL7vqBiZsaxcGv7srgi6YajSH3PspmSQg9BIp0"
            :key "Lz13coe3t17UVho7rfxjKA"}
 :secret "hUr1NAlaLHA0OqZvg6syrzFpAkKpNxCqIqzFgD0euo"
 :token "917913440-H5MvyRCXNEF9Fmekiwfba9cPpic8JfVQ65jae6Bn"}

(def creds (apply make-oauth-creds
                  ((juxt (comp :key :consumer)
                         (comp :secret :consumer)
                         :token :secret) config)))

;;;; helper

(defn format-url [user id] (format "https://twitter.com/%s/status/%s" user id))

(defn expand-url [url]
  (let [resp (client/get url)]
    (if-let [redirs (:trace-redirects resp)]
      (last redirs)
      url)))

(defn expand-twitter-urls [text]
  (s/replace text #"https*://t.co/\S+" expand-url))

(defn format-screen-name [json]
  (:screen_name (:user json)))

(defn format-media-urls [json]
  (info (:entities json))
  (->> (:entities json)
       :media
       (map :media_url)
       (join " ")))

(defn format-tweet-text [json]
  (str (:text json) " " (format-media-urls json)))

(defn format-tweet [json]
  (let [screen-name (format-screen-name json)
        url (format-url screen-name (:id json))
        retweeted-status (:retweeted_status json)
        text (if retweeted-status
               (str "RT " (format-screen-name retweeted-status) ": "
                    (format-tweet-text retweeted-status))
               (format-tweet-text json))]
    ; (info json)
    (format "%s — @%s %s"
            (-> text html-decode)
            ; (-> (:text json) expand-twitter-urls html-decode)
            screen-name url)))

(defn send-tweet [json]
  (chat/broadcast (format-tweet json)))

;;;; streaming callback

(defn succ [x y]
  (try
    (let [raw (str y)
          json (if-not (empty? raw) (json/read-json raw))]
      (if (and json (:user json))
        (send-tweet json)))
    (catch Exception e)))

(def fail (comp
            (fn [error-response] (error "twitter streaming error" error-response))
            response-return-everything))

(def exception (fn [exception] (error "twitter streaming exception" exception)))

(def streaming-callback (AsyncStreamingCallback. succ fail exception))

;;;; user stream

(defonce user-stream-resp
  (future (user-stream :oauth-creds creds :callbacks streaming-callback)))

;;;; search

(defn search [query]
  (info "twitter search for" query)
  (search-tweets
    :oauth-creds creds
    :params {:count 20 :q query :lang (:lang (:search config))}))

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

(defn retweet [id]
  (statuses-retweet-id :oauth-creds creds
                       :params {:id id}))

(defn reply [id status]
  (statuses-update :oauth-creds creds
                   :params {:in_reply_to_status_id id
                            :status status}))

;;;; users

(defn user-timeline [screen-name & tweet-count]
   (statuses-user-timeline :oauth-creds creds
                           :params {:screen-name screen-name
                                    :count (if-not (nil? tweet-count)
                                             tweet-count
                                             3)}))
;;;; show tweet with id

(defn show [id]
  (statuses-show-id :oauth-creds creds
                    :params {:id id}))
