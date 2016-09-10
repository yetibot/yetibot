(ns yetibot.commands.twitter
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :refer [join]]
    [yetibot.models.twitter :as model]
    [yetibot.core.hooks :refer [cmd-hook suppress]]))

(def limit-chars (comp join take))

(defn lookup
  "twitter lookup <screen-name> # look up info on Twitter user"
  [{[_ screen-name] :match}]
  (let [user (-> (model/user-timeline screen-name) :body first :user)]
    ((juxt :description :profile_image_url :location) user)))

(defn tweet
  "twitter tweet <status> # post <status> to Twitter"
  [{[_ status] :match}]
  (suppress (model/tweet (limit-chars 140 status))))

(defn following
  "twitter following # list Twitter users you are following"
  [_]
  (let [users (model/following)]
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

(defn show
  "twitter show <screen-name> # show top 10 tweets from user <scree-name>"
  [{[_ screen-name] :match}]
  (let [tweets (:body (model/user-timeline screen-name 10))]
    (map model/format-tweet-text tweets)))

(defn display
  "twitter show <id> # display tweet with <id>"
  [{[_ id] :match}]
  (model/show id))

(defn search
  "twitter search <query> # find most recent 20 tweets matching <query>"
  {:yb/cat #{:info}}
  [{[_ query] :match}]
  (->> (model/search query)
       :body
       :statuses
       (map model/format-tweet)))

(defn retweet
  "twitter retweet <id>"
  [{[_ id] :match}]
  (suppress (model/retweet id)))

(defn reply
  "twitter reply <id> <status> # note that the author's username of the referenced tweet must be mentioned"
  [{[_ id status] :match}]
  (suppress (model/reply id status)))

(if (model/configured?)
  (cmd-hook #"twitter"
    #"^lookup\s+(.+)" lookup
    #"^tweet:*\s+(.+)" tweet
    #"^following" following
    #"^follow\s+(.+)" follow
    #"^unfollow\s+(.+)" unfollow
    #"^search\s+(.+)" search
    #"^show\s+(\S+)" show
    #"^display\s+(\d+)" display
    #"^retweet\s+(\d+)" retweet
    #"^reply\s+(\d+)\s+(.+)" reply
    #"^tracking" tracking
    #"^untrack\s+(.+)" untrack
    #"^track\s+(.+)" track)
  (info "Twitter is not configured."))
