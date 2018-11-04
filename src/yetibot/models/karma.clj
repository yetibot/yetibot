(ns yetibot.models.karma
  (:require
   [yetibot.db.karma :as db]
   [clj-time.coerce :as coerce]))

(defn add-score-delta!
  [user-id voter-id points note]
  (db/create {:user-id user-id
              :voter-id voter-id
              :points points
              :note note}))

(defn get-score
  [user-id]
  (let [score (-> (db/query {:select/clause "SUM(points) as score"
                             :where/map {:user-id user-id}})
                  first :score)]
    (if (nil? score) 0 score)))

(defn get-notes
  [user-id]
  (map #(update % :created-at coerce/from-date)
       (db/query {:select/clause "note, voter_id, created_at"
                  :where/map {:user-id user-id}
                  :where/clause "note IS NOT NULL AND points > 0"
                  :order/clause "created_at DESC"
                  :limit/clause 3})))

(defn get-high-scores
  []
  (db/query {:select/clause "user_id, SUM(points) as score"
             :group/clause "user_id"
             :order/clause "score DESC"
             :limit/clause 10}))

(defn delete-user!
  [user-id]
  (doseq [id (map :id (db/query {:select/clause "id"
                                 :where/map {:user-id user-id}}))]
    (db/delete id)))

