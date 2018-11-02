(ns yetibot.core.models.karma
  (:require
   [yetibot.db.karma :as db]))

(defn add-score-delta
  [user-id voter-id points note]
  (db/create {:user-id user-id
              :voter-id voter-id
              :points points
              :note note}))

(defn get-score-for-user
  [user-id]
  (-> (db/query {:select/clause "SUM(points) as score"
                 :where/map {:user-id user-id}})
      first :score))

(defn get-notes-for-user
  [user-id]
  (db/query {:where/map {:user-id user-id}
             :where/clause "note IS NOT NULL AND points > 0"
             :order/clause "created_at DESC"
             :limit/clause 3}))

(defn get-high-scores
  []
  (db/query {:select/clause "user_id, SUM(points) as score"
             :group/clause "user_id"
             :order/clause "score DESC"
             :limit/clause 10}))
