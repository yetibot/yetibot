(ns yetibot.commands.status
  (:require [clj-time
              [format :refer [formatter]]
              [core :refer [default-time-zone time-zone-for-id]]
              [local :refer [local-now format-local-time *local-formatters*]]]
            [yetibot.hooks :refer [cmd-hook]]))

(defonce statuses (atom {}))
(def empty-msg "No one has set their status")
(def time-zone (time-zone-for-id "America/Los_Angeles"))

(defn- fmt-local-time []
  (binding [*local-formatters*
             {:short-time (formatter "hh:mm aa" time-zone)}]
    (format-local-time (local-now) :short-time)))

(defn set-status
  "status <message> # update your status"
  [{:keys [match user]}]
  (let [st (format "%s at %s: %s" (:name user) (fmt-local-time) match)]
    (swap! statuses conj {(:name user) st})
    "Status set"))

(defn show-status
  "status # show everyone's status"
  [_] (let [ss @statuses]
        (if (empty? ss) empty-msg (vals ss))))

(cmd-hook #"status"
          #".+" set-status
          _ show-status)
