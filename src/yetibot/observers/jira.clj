(ns yetibot.observers.jira
  (:require [yetibot.api.jira :as jira]
            [clojure.string :as s])
  (:use [yetibot.util]
        [yetibot.hooks :only [obs-hook]]
        [yetibot.util.http]
        [yetibot.chat :only (chat-data-structure)]
        [clojure.contrib.cond]))

(def project-keys
  (s/split (str (System/getenv "JIRA_PROJECT_KEYS")) #","))

; build a regex pattern to match jira issues
(def issue-pattern
  (re-pattern
    (str "(" (s/join "|" project-keys) ")" "-\\d+")))

(defn report-jira [issue]
  (prn (str "lookup jira issue " issue))
  (let [ji (jira/get-issue issue)
        fs (:fields ji)]
    (chat-data-structure [(-> fs :summary)
                  (str "Assignee: " (-> fs :assignee :displayName))
                  (str "Status: " (-> fs :status :name))
                  (str jira/base-uri "/browse/" issue)])))

(obs-hook
  #{:message}
  (fn [event-json]
    (if-let [is (set (map first (re-seq issue-pattern (:body event-json))))]
      (doall (map report-jira is)))))
