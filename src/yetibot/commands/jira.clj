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
  "jira projects # list configured projects (default project is marked with *)"
  (for [pk (api/project-keys)]
    (str
      (when (= pk (api/default-project-key)) "* ")
      (api/url-from-key pk))))

(defn users-cmd
  "jira users # list the users for the configured project(s)"
  [_]
  (map :name (api/get-users)))

(defn resolve-cmd
  "jira resolve <issue> <comment> # resolve an issue and set its resolution to fixed"
  [{[_ iss comment] :match user :user}]
  (let [comment (format "%s: %s" (:name user) comment)]
    (if-let [issue-data (api/get-issue iss)]
      (let [resolved? (api/resolve-issue iss comment)]
        (if resolved?
          (api/fetch-and-format-issue-short iss)
          (str "Unable to resolve issue " iss)))
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

(defn report-if-error
  "Checks the stauts of the HTTP response for 2xx, and if not, looks in the body
   for :errorMessages or :errors. To use this, make sure to use the
   `:throw-exceptions false`, `:content-type :json` and, `:coerce :always`
   options in the HTTP request."
  [res succ-fn]
  (if (success? res)
    (succ-fn res)
    (do
      (info "jira api error" res)
      (into
        ["👮 JIRA error 👮"]
        (or
          (seq (-> res :body :errorMessages))
          (map
            (fn [[k v]] (str (name k) ": " v))
            (-> res :body :errors)))))))

(def issue-opts
  [["-j" "--project-key PROJECT KEY" "Project key"]
   ["-c" "--component COMPONENT" "Component"]
   ["-s" "--summary SUMMARY" "Summary"]
   ["-a" "--assignee ASSIGNEE" "Assignee"]
   ["-f" "--fix-version FIX VERSION" "Fix version"]
   ["-d" "--desc DESCRIPTION" "Description"]
   ["-t" "--time TIME ESTIAMTED" "Time estimated"]
   ["-r" "--remaining REMAINING TIME ESTIAMTED" "Remaining time estimated"]
   ["-p" "--parent PARENT ISSUE KEY" "Parent issue key; creates a sub-task if specified"]])

(defn parse-issue-opts
  "Parse opts using issue-opts and trim all the values of the keys in options"
  [opts]
  (let [parsed (parse-opts (map trim (split opts #"(?=\s-\w)|(?<=\s-\w)")) issue-opts)]
    (update-in parsed [:options]
               (fn [options]
                 (into {} (map (fn [[k v]] [k (trim v)]) options))))))

(defn create-cmd
  "jira create <summary> -c <component> [-j project-key] [-a <assignee>] [-d <description>] [-f <fix-version>] [-t <time estimated>] [-p <parent-issue-key> (creates a sub-task if specified)]"
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
                                     (select-keys opts [:fix-version :project-key :parent :desc :assignee])
                                     (when (:time opts)
                                       {:timetracking {:originalEstimate (:time opts) :remainingEstimate (:time opts)}}))))]
        (report-if-error
          res
          (fn [res]
            (let [iss-key (-> res :body :key)]
              (api/fetch-and-format-issue-short iss-key))))))))

(defn update-cmd
  "jira update <issue-key> [-s <summary>] [-c <component>] [-a <assignee>] [-d <description>] [-f <fix-version>] [-t <time estimated>] [-r <remaining time estimated>]"
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
                    (select-keys opts [:fix-version :summary :desc :assignee])
                    (when (or (:remaining opts) (:time opts))
                      {:timetracking
                       (merge (when (:remaining opts) {:remainingEstimate (:remaining opts)})
                              (when (:time opts) {:originalEstimate (:time opts)}))}))))]
      (report-if-error
        res
        (fn [res]
          (info "updated" res)
          (let [iss-key (-> res :body :key)]
            (str "Updated: " (api/fetch-and-format-issue-short issue-key))))))))

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

(defn comment-cmd
  "jira comment <issue> <comment> # comment on <issue>"
  [{[_ iss-key body] :match user :user}]
  (let [body (format "%s: %s" (:name user) body)]
    (report-if-error
      (api/post-comment iss-key body)
      (fn [res] (report-jira iss-key) "Success"))))

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
  (short-jira-list (api/search jql)))

(defn components-cmd
  "jira components # list components and their leads by project"
  [_]
  (mapcat
    (fn [prj]
      (map
        (fn [c]
          (str
            "[" prj "] "
            "[" (-> c :lead :name) "] "
            (:name c)
            " — "
            (:description c)))
        (:body (api/components prj))))
    (api/project-keys)))

(defn format-version [v]
  (str (:name v)
       (when-let [rd (:releaseDate v)] (str " [release date " rd "]"))
       (when (:archived v) " [archived]")
       (when (:released v) " [released]")))

(defn versions-cmd
  "jira versions [<project-key>] # list versions for <project-key>. Lists versions for all configured project-keys if not specified."
  [{[_ project-key] :match}]
  (let [project-keys (if project-key [project-key] (api/project-keys))]
    (mapcat
      (fn [project-key]
        (map (fn [version] (str "[" project-key "] " (format-version version)))
             (:body (api/versions project-key))))
      project-keys)))

(defn parse-cmd
  "jira parse <text> # parse the issue key out of a jira issue URL"
  [{[_ text] :match}]
  (second (re-find #"browse\/([^\/]+)" text)))

(defn show-cmd
  "jira show <issue> # show the full details of an issue"
  [{[_ issue-key] :match}]
  (-> issue-key api/get-issue api/format-issue-long))

(defn delete-cmd
  "jira delete <issue> # delete the issue"
  [{[_ issue-key] :match}]
  (report-if-error
    (api/delete-issue issue-key)
    (fn [res]
      (info "deleted jira issue" issue-key res)
      (str "Deleted " issue-key))))

(cmd-hook #"jira"
  #"^projects" projects-cmd
  #"^parse\s+(.+)" parse-cmd
  #"^show\s+(\S+)" show-cmd
  #"^delete\s+(\S+)" delete-cmd
  #"^components" components-cmd
  #"^versions\s*(\S+)*" versions-cmd
  #"^recent" recent-cmd
  #"^pri" priorities-cmd
  #"^users" users-cmd
  #"^assign\s+(\S+)\s+(\S+)" assign-cmd
  #"^comment\s+(\S+)\s+(.+)" comment-cmd
  #"^search\s+(.+)" search-cmd
  #"^jql\s+(.+)" jql-cmd
  #"^create\s+(.+)" create-cmd
  #"^update\s+(\S+)\s+(.+)" update-cmd
  #"^resolve\s+([\w\-]+)\s+(.+)" resolve-cmd)
