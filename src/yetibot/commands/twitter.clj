(ns yetibot.commands.twitter
  (:require
    [clojure.string :refer [join]]
    [yetibot.models.twitter :as model]
    [yetibot.hooks :refer [cmd-hook]]))

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
          #"tracking" tracking
          #"untrack\s+(.+)" untrack
          #"track\s+(.+)" track)
