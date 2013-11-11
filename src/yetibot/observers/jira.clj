(ns yetibot.observers.jira
  (:require [yetibot.api.jira :as jira]
            [clojure.string :as s]
            [taoensso.timbre :refer [info warn error]]
            [yetibot.hooks :refer [obs-hook]]
            [yetibot.chat :refer [chat-data-structure]]))

(if jira/configured?
  (do
    (def project-keys (:project-keys jira/config))

    ; build a regex pattern to match jira issues
    (def issue-pattern
      (re-pattern
        (str "(" (s/join "|" project-keys) ")" "-\\d+")))

    (defn report-jira [issue]
      (let [ji (jira/get-issue issue)
            fs (:fields ji)]
        (chat-data-structure [(-> fs :summary)
                              (str "Assignee: " (-> fs :assignee :displayName))
                              (str "Status: " (-> fs :status :name))
                              (str jira/base-uri "/browse/" issue)])))

    (obs-hook
      #{:message}
      (fn [event-json]
        (prn "obs " event-json)
        (if-let [is (set (map first (re-seq issue-pattern (:body event-json))))]
          (doall (map report-jira is))))))
  (info "JIRA is not configured"))
