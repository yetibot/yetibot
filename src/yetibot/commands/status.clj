(ns yetibot.commands.status
  (:require [clj-time
              [format :refer [formatter unparse]]
              [core :refer [day year month
                            to-time-zone after?
                            default-time-zone now time-zone-for-id date-time utc]]]
            [yetibot.hooks :refer [cmd-hook]]))

; {user [{:timestamp DateTime :status "status"}]}
(defonce statuses (atom {}))

(def empty-msg "No one has set their status")
(def time-zone (time-zone-for-id "America/Los_Angeles"))
(def short-time (formatter "hh:mm aa" time-zone))

(defn- format-time [dt] (unparse short-time dt))

(defn- today [] (let [n (now)] (date-time (year n) (month n) (day n))))

(defn- is-today? [dt]
  (after? (to-time-zone dt time-zone) (to-time-zone (today) time-zone)))

(defn- add-status [curr user new-st]
  (update-in curr [user] #(conj % {:timestamp (now) :status new-st})))

(defn- format-sts
  "Transform statuses collection into a flattened collection of formatted strings,
  optionally filtered by `filter-fn`"
  ([ss] (format-sts ss identity))
  ([ss filter-fn]
   (->> ss
     ; first flatten the structure into [[user [:timestamp :status ]]]
     (mapcat (fn [[k vs]] (map #(vector k ((juxt :timestamp :status) %)) vs)))
     ; optionally filter out by some criteria
     (filter filter-fn)
     ; then sort it by timestamp
     (sort-by (comp second second))
     ; and finally format it
     (map (fn [[{n :name} [ts st]]] (format "%s at %s: %s" n (format-time ts) st))))))

(defn show-status
  "status # show all statuses for today"
  [_] (format-sts @statuses (fn [[_ [ts _]]] (is-today? ts))))

(defn set-status
  "status <message> # update your status"
  [{:keys [match user]}]
  (swap! statuses add-status user match)
  (show-status {:user user}))

(cmd-hook #"status"
          #".+" set-status
          _ show-status)
