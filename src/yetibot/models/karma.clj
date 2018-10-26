(ns yetibot.core.models.karma
  (:require
   [yetibot.db.karma :as db]))

(defn add-score-delta
  [user-id voter-id points note]
  (db/create {:user-id user-id
              :voter-id voter-id
              :points points
              :note note}))
