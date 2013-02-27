(ns yetibot.models.status
  (:require [clj-time
             [coerce :refer [from-date]]
             [format :refer [formatter unparse]]
             [core :refer [day year month
                           to-time-zone after?
                           default-time-zone now time-zone-for-id date-time utc
                           ago hours days weeks years months]]]
            [yetibot.models.users :refer [get-user]]
            [datomico.core :as dc]
            [datomico.db :refer [q]]))

;;;; schema

(def model-namespace :status)

(def schema (dc/build-schema model-namespace
                             [[:user-id :long]
                              [:status :string]]))

(dc/create-model-fns model-namespace)

;;;; time helpers

(def time-zone (time-zone-for-id "America/Los_Angeles"))
(def short-time (formatter "hh:mm aa" time-zone))
(defn- format-time [dt] (unparse short-time dt))
(defn- after-or-equal? [d1 d2] (or (= d1 d2) (after? d1 d2)))

;;;; write

(defn add-status [{:keys [id]} st]
  (create {:user-id id :status st}))

;;;; read

(defn- statuses
  "Retrieve statuses for all users"
  [] (seq (q '[:find ?user-id ?status ?txInstant
               :where
               [?tx :db/txInstant ?txInstant]
               [?i :status/user-id ?user-id ?tx]
               [?i :status/status ?status ?tx]])))

(defn status-since
  "Retrieve statuses after or equal to a given joda timestamp"
  [ts] (let [after-ts? (fn [[_ _ inst]] (after-or-equal? (from-date inst) ts))]
         (filter after-ts? (statuses))))

(def prepare-data
  "Turn user-ids into actual user maps and convert java dates to joda"
  (partial map (fn [[uid st inst]] [(get-user uid) st (from-date inst)])))

(def sort-st
  "Sort it by timestamp, descending"
  (partial sort-by (comp second rest) #(compare %2 %1)))

(def sts-to-strings
  "Format statuses collection as a collection of string"
  (partial map (fn [[user st date]]
                 (format "%s at %s: %s" (:name user) (format-time date) st))))

(defn format-sts
  "Transform statuses collection into a normalized collection of formatted strings"
  [ss]
  (-> ss
      prepare-data
      sort-st
      sts-to-strings))
