(ns yetibot.observers.jira
  (:require [yetibot.api.jira :as jira]
            [clojure.string :as s])
  (:use [yetibot.util]
        [yetibot.util.http]
        [clojure.contrib.cond]))

(def project-keys
  (s/split (str (System/getenv "JIRA_PROJECT_KEYS")) #","))

; build a regex pattern to match jira issues
(def issue-pattern
  (re-pattern
    (str "(" (s/join "|" project-keys) ")" "-\\d+")))

(defn report-jira [[issue & _]]
  (prn (str "lookup jira issue " issue))
  (let [ji (jira/get-issue issue)
        fs (:fields ji)]
    (chat-result [(-> fs :summary)
                  (str "Assignee: " (-> fs :assignee :displayName))
                  (str "Status: " (-> fs :status :name))
                  (str jira/base-uri "/browse/" issue)])))

(obs-hook
  ["TextMessage" "PasteMessage"]
  (fn [event-json]
    (prn event-json)
    (if-let [is (re-seq issue-pattern (:body event-json))]
      (doall (map report-jira is)))))
