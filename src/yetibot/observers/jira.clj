(ns yetibot.observers.jira
  (:require
    [yetibot.api.jira :as jira]
    [clojure.string :as s]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [obs-hook]]
    [yetibot.core.chat :refer [chat-data-structure]]))

(defn report-jira [issue]
  (let [ji (jira/get-issue issue)]
    (chat-data-structure
      (jira/format-issue ji))))

(defn start []
  (let [project-keys (jira/project-keys)
        issue-pattern (re-pattern
                        ; build a regex pattern to match jira issues
                        (str "(" (s/join "|" project-keys) ")" "-\\d+"))]
    (obs-hook
      #{:message}
      (fn [event-json]
        ; ignore issues mentioned in commands
        (when-not (re-find #"^!" (:body event-json))
          (when-let [is (set (map first (re-seq issue-pattern (:body event-json))))]
            (doall (map report-jira is))))))))

(if (jira/configured?)
  (start)
  (info "JIRA is not configured"))
