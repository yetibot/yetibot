(ns yetibot.commands.jira
  (:require
    [yetibot.observers.jira :refer [report-jira]]
    [yetibot.core.util :refer [filter-nil-vals map-to-strs]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.api.jira :as api]))


(defn projects-cmd
  [_]
  "jira projects # list configured projects"
  (->> api/config :project-keys
      (map api/url-from-key)))

(defn users-cmd
  "jira users # list the users for the configured project(s)"
  [_]
  (map :name (api/get-users)))

(defn resolve-cmd
  "jira resolve <issue> <comment> # resolve an issue and set its resolution to fixed"
  [{[_ iss comment] :match user :user}]
  (let [comment (format "%s: %s" (:name user) comment)]
    (if-let [issue-data (api/get-issue iss)]
      (let [resolved? (api/resolve-issue iss comment)
            ; refetch the issue data now that it's resolved
            issue-data (api/get-issue iss)
            formatted (api/format-issue issue-data)]
        (if resolved?
          formatted
          (into [(str "Unable to resolve issue " iss)] formatted)))
      (str "Unable to find any issues for " iss))))

(defn priorities-cmd
  "jira pri # list the priorities for this JIRA instance"
  [_]
  (->> (api/priorities)
       (map (juxt :name :description))
       flatten
       (apply sorted-map)))

(defn success? [res]
  (re-find #"^2" (str (:status res) "2")))

(defn report-if-error [res succ-fn]
  (if (success? res)
    (succ-fn res)
    (-> res :body :errorMessages)))

; currently doesn't support more than one project key, but it could
(defn create-cmd
  "jira create <summary> # create issue with summary, unassigned
   jira create <summary> / <assignee> # create issue with summary and assignee
   jira create <summary> / <assignee> / <desc> # create issue with summary, assignee, and description"
  [{[_ summary assignee desc] :match}]
  (let [res (api/create-issue
              (filter-nil-vals {:summary summary
                                :assignee assignee
                                :desc desc}))]
    (if (success? res)
      (let [iss-key (-> res :body :key)]
        (api/fetch-and-format-issue-short iss-key))
      (map-to-strs (->> res :body :errors)))))

(defn- short-jira-list [res]
  (if (success? res)
    (map api/format-issue-short
         (->> res
              :body
              :issues
              (take 5)))
    (-> res :body :errorMessages)))

(defn assign-cmd
  "jira assign <issue> <assignee> # assign <issue> to <assignee>"
  [{[_ iss-key assignee] :match}]
  (report-if-error
    (api/assign-issue iss-key assignee)
    (fn [res] (report-jira iss-key) "Success")))

(defn recent-cmd
  "jira recent # show the 5 most recent issues"
  [_]
  (short-jira-list (api/recent)))

(defn search-cmd
  "jira search <query> # return up to 5 issues matching <query> across all configured projects"
  [{[_ query] :match}]
  (short-jira-list (api/search-by-query query)))

(defn jql-cmd
  "jira jql <jql> # return up to 5 issues matching <jql> query across all configured projects"
  [{[_ jql] :match}]
  (short-jira-list (api/search-in-projects jql)))

(defn components-cmd
  "jira components # list components across all configured projects"
  [_]
  (mapcat (comp (partial map :name) :body) (api/all-components)))

(cmd-hook #"jira"
          #"^projects" projects-cmd
          #"^components" components-cmd
          #"^recent" recent-cmd
          #"^pri" priorities-cmd
          #"^users" users-cmd
          #"^assign\s+(\S+)\s+(\S+)" assign-cmd
          #"^search\s+(.+)" search-cmd
          #"^jql\s+(.+)" jql-cmd
          #"^create\s+([^\/]+)\s+\/\s+([^\/]+)\s+\/\s+(.+)" create-cmd
          #"^create\s+([^\/]+)\s+\/\s+([^\/]+)" create-cmd
          #"^create\s+([^\/]+)" create-cmd
          #"^resolve\s+([\w\-]+)\s+(.+)" resolve-cmd)
