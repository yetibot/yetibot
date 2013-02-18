(ns yetibot.test.models.status
  (:require [midje.sweet :refer [fact]]
            [yetibot.models.status :refer :all]
            [clj-time
              [format :refer [formatter unparse]]
              [core :refer [day year month
                            to-time-zone after?
                            default-time-zone now time-zone-for-id date-time utc
                            ago days weeks years months]]]))

; helpers
(defn- n-days-ago [n] (at-midnight (-> n days ago)))
(defn- status-map-for-n-days-ago [n]
  {:timestamp (n-days-ago n)
   :status (str "days ago: " n)})
(defn- flat-status-map-n-days-ago [n]
  ((juxt :timestamp :status) (status-map-for-n-days-ago n)))
(defn- u [id] {:name (str id) :id id}) ;; user stub

(fact "about limiting statuses to given time periods"
  (status-since (n-days-ago 0)) => {(u 123) [(status-map-for-n-days-ago 0)]}
  (against-background
    (before :facts (reset! statuses {(u 123) (map status-map-for-n-days-ago (range 8))}))))

(fact "about sorting maps by a DateTime value within them"
  (let [sts-for-sorting {(u 123) (map status-map-for-n-days-ago [1])
                         (u 234) (map status-map-for-n-days-ago [2])
                         (u 345) (map status-map-for-n-days-ago [0])}
        fs-sorted [[(u 345) (flat-status-map-n-days-ago 0)]
                   [(u 123) (flat-status-map-n-days-ago 1)]
                   [(u 234) (flat-status-map-n-days-ago 2)]]]
    (->> sts-for-sorting flatten-sts sort-fs) => fs-sorted))
