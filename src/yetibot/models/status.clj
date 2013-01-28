(ns yetibot.models.status
  (:require [yetibot.models.users :as u]
            [clj-time
              [format :refer [formatter unparse]]
              [core :refer [day year month
                            to-time-zone after?
                            default-time-zone now time-zone-for-id date-time utc]]]))

; {user [{:timestamp DateTime :status "status"}]}
(defonce statuses (atom {}))

; time
(def time-zone (time-zone-for-id "America/Los_Angeles"))
(def short-time (formatter "hh:mm aa" time-zone))
(defn- format-time [dt] (unparse short-time dt))
(defn- today [] (let [n (now)] (date-time (year n) (month n) (day n))))
(defn- is-today? [dt]
  (after? (to-time-zone dt time-zone) (to-time-zone (today) time-zone)))



(defn add-status [{:keys [id]} st]
  (let [update (fn [curr user-id new-st]
                 (update-in curr [user-id] #(conj % {:timestamp (now) :status new-st})))]
    (swap! statuses update id st)))


(defn- format-sts
  "Transform statuses collection into a flattened collection of formatted strings,
  optionally filtered by `filter-fn`"
  ([ss] (format-sts ss identity))
  ([ss filter-fn]
   (->> ss
     ; flatten the structure into [[user [:timestamp :status ]]]
     (mapcat (fn [[k vs]] (map #(vector k ((juxt :timestamp :status) %)) vs)))
     ; optionally filter out by some criteria
     (filter filter-fn)
     ; sort it by timestamp
     (sort-by (comp second second))
     ; and finally format it
     (map (fn [[id [ts st]]]
            (format "%s at %s: %s" (:name (u/get-user id)) (format-time ts) st))))))

(defn statuses-for-today []
  (format-sts @statuses (fn [[_ [ts _]]] (is-today? ts))))
