(ns yetibot.db.image-budget
  (:require [yetibot.core.db.util :as db.util]))

(def schema
  {:schema/table "image_budget"
   :schema/specs (into [[:month :text "NOT NULL"]
                        [:image-count :integer "NOT NULL" "DEFAULT 0"]]
                       (db.util/default-fields))})

(def create (partial db.util/create (:schema/table schema)))

(def query (partial db.util/query (:schema/table schema)))

(def update-where (partial db.util/update-where (:schema/table schema)))
