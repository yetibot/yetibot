(ns yetibot.observers.jira
  (:require
    [yetibot.core.models.channel :as channel]
    [yetibot.api.jira :as jira
     :refer [jira-project-setting-key channel-projects]]
    [clojure.string :as s]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [obs-hook]]
    [yetibot.core.chat :refer [chat-data-structure]]))

(defn report-jira [issue]
  (if-let [ji (:body (try
                       (jira/get-issue issue)
                       (catch Exception e nil)))]
    (chat-data-structure
      (jira/format-issue-short ji))
    (info "issue" issue "not found")))

(defn issue-pattern [chan-projects]
  (let [project-keys (into (jira/project-keys) chan-projects)]
    (re-pattern
      ; build a regex pattern to match jira issues
      (str "(" (s/join "|" project-keys) ")" "-\\d+"))))

(defn jira-observer [{:keys [body chat-source] :as event-json}]
  ; ignore issues mentioned in commands
  (when-not (re-find #"^!" body)
    (let [channel-settings
          (channel/settings-for-chat-source chat-source)
          chan-projects (channel-projects channel-settings)]
      (when-let [is (set (map first
                              (re-seq (issue-pattern chan-projects) body)))]
        (info "found issues" (pr-str is))
        (doall (map report-jira is))))))

(defn start [] (obs-hook #{:message} #'jira-observer))

(when (jira/configured?)
  (info "Starting jira observer")
  ;; ensure a single observer
  (defonce jira-observer-hook (start)))


(comment
  ;; if you need to reload the obs
  (ns-unmap *ns* 'jira-observer-hook)
  )
