(ns yetibot.commands.status
  (:require [yetibot.models.status :as model]
            [clj-time [core :refer [ago minutes hours days weeks years months]]]
            [yetibot.hooks :refer [cmd-hook]]))

(def empty-msg "No one has set their status")

(defn show-status
  "status # show statuses in the last 8 hours"
  [_] (model/format-sts (model/status-since (-> 8 hours ago))))

(defn set-status
  "status <message> # update your status"
  [{:keys [match user]}]
  (model/add-status user match)
  (show-status {:user user}))

(cmd-hook #"status"
          #".+" set-status
          _ show-status)
