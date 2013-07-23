(ns yetibot.commands.twitter
  (:require
    [clojure.string :refer [join]]
    [yetibot.models.twitter :as model]
    [yetibot.hooks :refer [cmd-hook suppress]]))

(def limit-chars (comp join take))

(defn tweet
  "twitter tweet <status> # post <status> to Twitter"
  [{[_ status] :match}]
  (suppress (model/tweet (limit-chars 140 status))))

(defn following
  "twitter following # list Twitter users you are following"
  [_]
  (let [users (:users (:body (model/following)))]
    (map :screen_name users)))

(defn follow
  "twitter follow <screen-name> # follow a Twitter user"
  [{[_ screen-name] :match}]
  (let [body (:body (model/follow screen-name))
        [img desc] ((juxt :profile_image_url :description) body)]
    [(str "Followed " screen-name ": " desc)
     img]))

(defn unfollow
  "twitter unfollow <screen-name> # stop following a Twitter user"
  [{[_ screen-name] :match}]
  (model/unfollow screen-name)
  (str "Unfollowed " screen-name))

(defn tracking
  "twitter tracking # show the topics that are being tracked on Twitter"
  [_] (let [topics (map :topic (model/find-all))]
        (if (empty? topics)
          "You're not tracking anything yet."
          topics)))

(defn track
  "twitter track <topic> # track a <topic> on the Twitter stream"
  [{[_ topic] :match user :user}]
  (if (model/find-first {:topic topic})
    (format "You're already tracking %s." topic)
    (do
      (model/add-topic (:id user) topic)
      (format "Tracking %s" topic))))

(defn untrack
  "twitter untrack <topic> # stop tracking <topic>"
  [{[_ topic] :match}]
  (if-let [topic-entity (model/find-first {:topic topic})]
    (do
      (model/remove-topic (:id topic-entity))
      (format "Stopped tracking %s" topic))
    (format "You're not tracking %s" topic)))

(cmd-hook #"twitter"
          #"^tweet\s+(.+)" tweet
          #"^following" following
          #"^follow\s+(.+)" follow
          #"^unfollow\s+(.+)" unfollow
          #"^tracking" tracking
          #"^untrack\s+(.+)" untrack
          #"^track\s+(.+)" track)
