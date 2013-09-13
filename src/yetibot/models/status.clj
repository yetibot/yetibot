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
                             [[:user-id :string]
                              [:chat-source :string]
                              [:status :string]
                              [:created-at :instant]]))

(dc/create-model-fns model-namespace)

;;;; time helpers

(def time-zone (time-zone-for-id "America/Los_Angeles"))
(def short-time (formatter "hh:mm aa" time-zone))
(defn- format-time [dt] (unparse short-time dt))
(defn- after-or-equal? [d1 d2] (or (= d1 d2) (after? d1 d2)))

;;;; write

(defn add-status [{:keys [id]} chat-source st]
  (create {:user-id (str id) :chat-source chat-source
           :status st :created-at (java.util.Date.)}))

;;;; read

(defn- statuses
  "Retrieve statuses for all users"
  [chat-source]
  (find-all {:chat-source chat-source}))

;;
(comment
  (add-status {:id 993269} "campfire/523232" "foo")

  (add-status {:id "devth"} "irc/#yeti" "irc stats")

  (find-all)

  (statuses "campfire/523232" )

  ({:id 17592186045418, :user-id 993269, :chat-source "campfire/523232", :status "hi"}
   {:id 17592186045420, :user-id 993269, :chat-source "campfire/523232", :status
    "hello"} {:id 17592186045422, :user-id 993269, :chat-source "campfire/523232",
              :status "foo"})


  (def chat-source "campfire/523232" )
  "campfire/523232"

  ({:id 17592186045418, :user-id "993269", :chat-source "campfire/523232", :status "foo"}
   {:id 17592186045420, :user-id "devth", :chat-source "irc/#yeti", :status "irc stats"})

  (seq (q '[:find ?user-id ?status ?txInstant
            :where
            [?tx :db/txInstant ?txInstant]
            [?i :status/user-id ?user-id]
            [?i :status/status ?status]
            [?i :status/chat-source "campfire/523232"]
            ]))
)

(def ^:private prepare-data
  "Turn user-ids into actual user maps and convert java dates to joda"
  (partial map (fn [{:keys [user-id status created-at]}]
                 [(get-user yetibot.interpreter/*chat-source* user-id) status (from-date created-at)])))

(def ^:private sort-st
  "Sort it by timestamp, descending"
  (partial sort-by (comp second rest) #(compare %2 %1)))

(def ^:private sts-to-strings
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

(defn status-since
  "Retrieve statuses after or equal to a given joda timestamp"
  [chat-source ts]
  (let [after-ts? (fn [{:keys [created-at]}]
                    (after-or-equal? (from-date created-at) ts))]
    (filter after-ts? (statuses chat-source))))
