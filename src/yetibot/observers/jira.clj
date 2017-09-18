(ns yetibot.observers.jira
  (:require
    [yetibot.api.jira :as jira]
    [clojure.string :as s]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [obs-hook]]
    [yetibot.core.chat :refer [chat-data-structure]]))

(defn report-jira [issue]
  (if-let [ji (jira/get-issue issue)]
    (chat-data-structure
      (jira/format-issue-short ji))
    (info "issue" issue "not found")))

(defn issue-pattern []
  (let [project-keys (jira/project-keys)]
    (re-pattern
      ; build a regex pattern to match jira issues
      (str "(" (s/join "|" project-keys) ")" "-\\d+"))))

(defn jira-observer [event-json]
  ; ignore issues mentioned in commands
  (when-not (re-find #"^!" (:body event-json))
    (when-let [is (set (map first (re-seq (issue-pattern) (:body event-json))))]
      (doall (map report-jira is)))))

(defn start [] (obs-hook #{:message} #'jira-observer))

(when (jira/configured?)
  (info "Starting jira observer")
  ;; ensure a single observer
  (defonce jira-observer-hook (start)))
