(ns yetibot.models.status
  (:require [yetibot.models.users :as u]
            [clj-time
              [format :refer [formatter unparse]]
              [core :refer [day year month
                            to-time-zone after?
                            default-time-zone now time-zone-for-id date-time utc
                            ago days weeks years months]]]))

; {user [{:timestamp DateTime :status "status"}]}
(defonce statuses (atom {}))

; time
(def time-zone (time-zone-for-id "America/Los_Angeles"))
(def short-time (formatter "hh:mm aa" time-zone))
(defn- format-time [dt] (unparse short-time dt))
(defn at-midnight [dt] (date-time (year dt) (month dt) (day dt)))
(defn- today [] (at-midnight (now)))
(defn- is-today? [dt]
  (after? (to-time-zone dt time-zone) (to-time-zone (today) time-zone)))
(defn- after-or-equal? [d1 d2] (or (= d1 d2) (after? d1 d2)))

(defn add-status [{:keys [id]} st]
  (let [update (fn [curr user-id new-st]
                 (update-in curr [user-id] #(conj % {:timestamp (now) :status new-st})))]
    (swap! statuses update id st)))


(defn flatten-sts
  "flatten the structure into [[user [:timestamp :status ]]]"
  [sts]
  (mapcat (fn [[k vs]] (map #(vector k ((juxt :timestamp :status) %)) vs)) sts))

(defn sort-fs
  "sort it by timestamp"
  [flat-sts] (sort-by (comp second second) flat-sts))

(defn format-sts
  "Transform statuses collection into a flattened collection of formatted strings,
  optionally filtered by `filter-fn`"
  ([ss] (format-sts ss identity))
  ([ss filter-fn]
   (->> ss
     flatten-sts
     ; optionally filter out by some criteria
     (filter filter-fn)
     sort-fs
     ; and finally format it
     (map (fn [[id [ts st]]]
            (format "%s at %s: %s" (:name (u/get-user id)) (format-time ts) st))))))

(defn status-since [ts]
  (letfn [(after-ts? [status-item] (after-or-equal? (:timestamp status-item) ts))]
    (map (fn [[id sts]] {id (filter after-ts? sts)}) @statuses)))

(defn statuses-for-today []
  (format-sts @statuses (fn [[_ [ts _]]] (is-today? ts))))
