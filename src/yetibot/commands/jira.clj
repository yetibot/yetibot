(ns yetibot.commands.jira
  (:require
    [clojure.tools.cli :refer [parse-opts]]
    [yetibot.observers.jira :refer [report-jira]]
    [yetibot.core.util :refer [filter-nil-vals map-to-strs]]
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :refer [split join trim]]
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

(def issue-opts
  [["-c" "--component COMPONENT" "Component"]
   ["-s" "--summary SUMMARY" "Summary"]
   ["-a" "--assignee ASSIGNEE" "Assignee"]
   ["-d" "--desc DESCRIPTION" "Description"]
   ["-t" "--time TIME ESTIAMTED" "Time estimated"]
   ["-r" "--remaining REMAINING TIME ESTIAMTED" "Remaining time estimated"]
   ["-p" "--parent PARENT ISSUE KEY" "Parent issue key; creates a sub-task if specified"]])

(defn parse-issue-opts [opts]
  (parse-opts (map trim (split opts #"(?=\s-\w)|(?<=\s-\w)")) issue-opts))

; currently doesn't support more than one project key, but it could
(defn create-cmd
  "jira create <summary> -c <component> [-a <assignee>] [-d <description>] [-t <time estimated>] [-p <parent-issue-key> (creates a sub-task if specified)]"
  [{[_ opts-str] :match}]
  (let [parsed (parse-issue-opts opts-str)
        summary (->> parsed :arguments (join " "))
        opts (:options parsed)]
    (if-not (:component opts)
      "Component is required when creating JIRA issues"
      (let [component-ids (map :id (api/find-component-like (:component opts)))
            res (api/create-issue
                  (filter-nil-vals (merge
                                     {:summary summary :component-ids component-ids}
                                     (select-keys opts [:parent :desc :assignee])
                                     (when (:time opts)
                                       {:timetracking {:originalEstimate (:time opts) :remainingEstimate (:time opts)}}))))]
        (if (success? res)
          (let [iss-key (-> res :body :key)]
            (api/fetch-and-format-issue-short iss-key))
          (report-errors res))))))

(defn report-errors [res]
  (info "report errors for" res)
  (or (->> res :body :errorMessages) (->> res :body :errors map-to-strs)))

(defn update-cmd
  "jira update <issue-key> [-s <summary>] [-c <component>] [-a <assignee>] [-d <description>] [-t <time estimated>] [-r <remaining time estimated>]"
  [{[_ issue-key opts-str] :match}]
  (let [parsed (parse-issue-opts opts-str)
        opts (:options parsed)]
    (clojure.pprint/pprint parsed)
    (let [component-ids (when (:component opts) (map :id (api/find-component-like (:component opts))))
          res (api/update-issue
                issue-key
                (filter-nil-vals
                  (merge
                    {:component-ids component-ids}
                    (select-keys opts [:summary :desc :assignee])
                    (when (or (:remaining opts) (:time opts))
                      {:timetracking
                       (merge (when (:remaining opts) {:remainingEstimate (:remaining opts)})
                              (when (:time opts) {:originalEstimate (:time opts)}))}))))]
      (if (success? res)
        (let [iss-key (-> res :body :key)]
          (str "Updated: "
               (api/fetch-and-format-issue-short issue-key)))
        (report-errors res)))))

(defn- short-jira-list [res]
  (if (success? res)
    (map api/format-issue-short
         (->> res :body :issues (take 15)))
    (-> res :body :errorMessages)))

(defn assign-cmd
  "jira assign <issue> <assignee> # assign <issue> to <assignee>"
  [{[_ iss-key assignee] :match}]
  (report-if-error
    (api/assign-issue iss-key assignee)
    (fn [res] (report-jira iss-key) "Success")))

(defn recent-cmd
  "jira recent # show the 15 most recent issues"
  [_]
  (short-jira-list (api/recent)))

(defn search-cmd
  "jira search <query> # return up to 15 issues matching <query> across all configured projects"
  [{[_ query] :match}]
  (short-jira-list (api/search-by-query query)))

(defn jql-cmd
  "jira jql <jql> # return up to 15 issues matching <jql> query across all configured projects"
  [{[_ jql] :match}]
  (short-jira-list (api/search-in-projects jql)))

(defn components-cmd
  "jira components # list components across all configured projects"
  [_]
  (mapcat (comp (partial map :name) :body) (api/all-components)))

(defn parse-cmd
  [{[_ text] :match}]
  (second (re-find #"browse\/([^\/]+)" text)))

(cmd-hook #"jira"
          #"^projects" projects-cmd
          #"^parse\s+(.+)" parse-cmd
          #"^components" components-cmd
          #"^recent" recent-cmd
          #"^pri" priorities-cmd
          #"^users" users-cmd
          #"^assign\s+(\S+)\s+(\S+)" assign-cmd
          #"^search\s+(.+)" search-cmd
          #"^jql\s+(.+)" jql-cmd
          #"^create\s+(.+)" create-cmd
          #"^update\s+(\S+)\s+(.+)" update-cmd
          #"^resolve\s+([\w\-]+)\s+(.+)" resolve-cmd)
