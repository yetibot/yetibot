(ns yetibot.db.karma
  (:require
   [yetibot.core.db.util :as db.util]))

(def schema {:schema/table "karma"
             :schema/specs (into [[:user-id :text "NOT NULL"]
                                  [:points :integer "NOT NULL"]
                                  [:voter-id :text "NOT NULL"]
                                  [:note :text]]
                                 (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))
