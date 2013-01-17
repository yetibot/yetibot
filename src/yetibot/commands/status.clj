(ns yetibot.commands.status
  (:require [yetibot.models.status :as model]
            [yetibot.hooks :refer [cmd-hook]]))

(def empty-msg "No one has set their status")

(defn show-status
  "status # show all statuses for today"
  [_] (model/statuses-for-today))

(defn set-status
  "status <message> # update your status"
  [{:keys [match user]}]
  (model/add-status user match)
  (show-status {:user user}))

(cmd-hook #"status"
          #".+" set-status
          _ show-status)
