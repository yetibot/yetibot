(ns yetibot.commands.status
  (:require [yetibot.models.status :as model]
            [clj-time [core :refer [ago minutes hours days weeks years months]]]
            [inflections.core :refer [plural]]
            [yetibot.hooks :refer [cmd-hook]]))

(def empty-msg "No one has set their status")

(defn show-status
  "status # show statuses in the last 8 hours"
  [{:keys [chat-source]}] (model/format-sts (model/status-since chat-source (-> 8 hours ago))))

(defn show-status-since
  "status since <n> <minutes|hours|days|weeks|months> ago # show status since a given time" 
  [{[_ n unit] :match chat-source :chat-source}]
  (let [unit (plural unit) ; pluralize if singular
        unit-fn (ns-resolve 'clj-time.core (symbol unit))
        n (read-string n)]
    (if (number? n)
      (model/format-sts (model/status-since chat-source (-> n unit-fn ago)))
      (str n " is not a number"))))

(defn set-status
  "status <message> # update your status"
  [{:keys [match chat-source user] :as args}]
  (model/add-status user chat-source match)
  (show-status args))

(cmd-hook #"status"
          #"since (.+) (minutes*|hours*|days*|weeks*|months*)( ago)*" show-status-since
          #".+" set-status
          _ show-status)
