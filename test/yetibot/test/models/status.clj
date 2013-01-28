(ns yetibot.test.models.status
  (:require [midje.sweet :refer [fact]]
            [yetibot.models.status :refer :all]
            [clj-time
              [format :refer [formatter unparse]]
              [core :refer [day year month
                            to-time-zone after?
                            default-time-zone now time-zone-for-id date-time utc
                            ago days weeks years months]]]))

(defn n-days-ago [n] (at-midnight (-> n days ago)))

(defn status-map-for-n-days-ago [n]
  {:timestamp (n-days-ago n)
   :status (str "days ago: " n)})

; fixture
(reset! statuses {123 (map status-map-for-n-days-ago (range 8))})

(fact "about limiting statuses to given time periods"
  (status-since (n-days-ago 0)) => [{123 [(status-map-for-n-days-ago 0)]}])
