(ns yetibot.commands.twitter
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :refer [join]]
    [yetibot.models.twitter :as model]
    [yetibot.core.chat :refer [suppress]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def limit-chars (comp join take))

(defn lookup
  "twitter lookup <screen-name> # look up info on Twitter user"
  [{[_ screen-name] :match}]
  (let [user (-> (model/user-timeline screen-name) :body first :user)]
    {:result/data user
     :result/value ((juxt :description :profile_image_url :location) user)}))

(defn tweet
  "twitter tweet <status> # post <status> to Twitter"
  [{[_ status] :match}]
  (suppress (model/tweet (limit-chars 140 status))))

(defn following
  "twitter following # list Twitter users you are following"
  [_]
  (let [users (model/following)]
    {:result/data users
     :result/value (map :screen_name users)}))

(defn follow
  "twitter follow <screen-name> # follow a Twitter user"
  [{[_ screen-name] :match}]
  (let [body (:body (model/follow screen-name))
        [img desc] ((juxt :profile_image_url :description) body)]
    {:result/data body
     :result/value [(str "Followed " screen-name ": " desc)
                    img]}))

(defn unfollow
  "twitter unfollow <screen-name> # stop following a Twitter user"
  [{[_ screen-name] :match}]
  {:result/data (model/unfollow screen-name)
   :result/value (str "Unfollowed " screen-name)})

(defn tracking
  "twitter tracking # show the topics that are being tracked on Twitter"
  [_]
  (let [topics (model/find-all)]
    (if (empty? topics)
      "You're not tracking anything yet."
      {:result/data topics
       :result/value (map :topic topics)})))

(defn track
  "twitter track <topic> # track a <topic> on the Twitter stream"
  [{[_ topic] :match user :user}]
  (if (model/find-by-topic topic)
    (format "You're already tracking %s." topic)
    {:result/data (model/add-topic (:id user) topic)
     :result/value (format "Tracking %s" topic)}))

(defn untrack
  "twitter untrack <topic> # stop tracking <topic>"
  [{[_ topic] :match}]
  (if-let [{topic-id :id} (first (model/find-by-topic topic))]
    {:result/data (model/remove-topic topic-id)
     :result/value (format "Stopped tracking %s" topic)}
    {:result/error (format "You're not tracking %s" topic)}))

(defn show
  "twitter show <screen-name> # show top 10 tweets from user <scree-name>"
  [{[_ screen-name] :match}]
  (let [tweets (:body (model/user-timeline screen-name 10))]
    {:result/data tweets
     :result/value (map model/format-tweet-text tweets)}))

(defn display
  "twitter display <id> # display tweet with <id>"
  [{[_ id] :match}]
  (let [{body :body} (model/show id)]
    {:result/data body
     :result/value (model/format-tweet body)}))

(defn search
  "twitter search <query> # find most recent 20 tweets matching <query>"
  {:yb/cat #{:info}}
  [{[_ query] :match}]
  (let [{{statuses :statuses} :body} (model/search query)]
    {:result/data statuses
     :result/value (map model/format-tweet statuses)}))

(defn retweet
  "twitter retweet <id>"
  [{[_ id] :match}]
  (suppress (model/retweet id)))

(defn reply
  "twitter reply <id> <status> # note that the author's username of the referenced tweet must be mentioned"
  [{[_ id status] :match}]
  (suppress (model/reply id status)))

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
