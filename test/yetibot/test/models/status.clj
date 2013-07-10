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
(defn- n-days-ago [n] (-> n days ago))
(defn- status-map-for-n-days-ago [n]
  {:timestamp (n-days-ago n)
   :status (str "days ago: " n)})
(defn- flat-status-map-n-days-ago [n]
  ((juxt :timestamp :status) (status-map-for-n-days-ago n)))
(defn- u [id] {:name (str id) :id id}) ;; user stub

(fact "about limiting statuses to given time periods")
(fact "about sorting maps by a DateTime value within them")
