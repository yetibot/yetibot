(ns yetibot.db.twitter
  (:require
    [yetibot.core.db.util :as db.util]))

(def schema {:schema/table "twitter"
             :schema/specs (into [[:user-id :text "NOT NULL"]
                                  [:topic :text "NOT NULL"]]
                                 (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def delete (partial db.util/delete (:schema/table schema)))

(def find-all (partial db.util/find-all (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))
