(ns yetibot.api.jira
  (:require
    [taoensso.timbre :refer [info warn error]]
    [schema.core :as sch]
    [yetibot.core.schema :refer [non-empty-str]]
    [clojure.string :as s]
    [clj-http.client :as client]
    [clojure.core.memoize :as memo]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util.http :refer [get-json fetch]]
    [clj-time [format :refer [formatter formatters show-formatters parse unparse]]]))

(def jira-schema
  {:domain non-empty-str
   :user non-empty-str
   :password non-empty-str
   ;; deprecate this in favor of channel based projects
   (sch/optional-key :projects) [{:key non-empty-str
                                  (sch/optional-key :default)
                                  {:version {:id non-empty-str}}}]
   (sch/optional-key :default)
   {(sch/optional-key :issue) {:type {:id non-empty-str}}
    (sch/optional-key :project) {:key non-empty-str}}
   (sch/optional-key :max) {:results non-empty-str}
   (sch/optional-key :subtask) {:issue {:type {:id non-empty-str}}}})

;; config

(def ^:dynamic *jira-project*
  "Settings for the current channel, bound by yetibot.commands.jira"
  nil)

(def ^:dynamic *jira-projects*
  "Settings for the current channel, bound by yetibot.commands.jira"
  nil)

(def jira-project-setting-key
  "This key is used to store channel-specific JIRA projects"
  "jira-project")

(defn channel-projects
  "Retrieve the list of configured projects for a channel, given its settings"
  [channel-settings]
  (when-let [setting (channel-settings jira-project-setting-key)]
    (info "channel-projects" (pr-str setting))
    (seq (remove s/blank? (s/split setting #",\s*")))))

(defn config [] (:value (get-config jira-schema [:jira])))

(defn configured? [] (config))

(defn projects [] (:projects (config)))

(defn project-for-key [k] (first (filter #(= (:key %) k) (projects))))

(defn project-keys [] (into (vec *jira-projects*)
                            (map :key (projects))))

(defn project-keys-str [] (s/join "," (into
                                        (project-keys)
                                        *jira-projects*)))

(defn default-version-id [project-key] (-> (project-for-key project-key)
                                           :default :version :id))

(defn default-project-key [] (or *jira-project*
                                 (first *jira-projects*)
                                 (-> (config) :default :project :key)
                                 (first (project-keys))))

(defn default-project [] (project-for-key (default-project-key)))

(defn max-results []
  (if-let [mr (-> (config) :max :results)]
    (read-string mr)
    10))

(defn sub-task-issue-type-id [] (-> (config) :subtask :issue :type :id ))

(defn default-issue-type-id [] (-> (config) :default :issue :type :id))

(defn base-uri [] (str "https://" (:domain (config))))

(defn api-uri [] (str (base-uri) "/rest/api/latest"))

(def auth (map (config) [:user :password]))

;; formatters
;; move to yetibot.core util if anyone else needs date parsing and formatting:

(def date-time-format (:date-hour-minute formatters))
(defn parse-and-format-date-string [date-string]
  (unparse date-time-format (parse date-string)))

(def client-opts {:as :json
                  :basic-auth auth
                  :throw-exceptions true
                  :coerce :unexceptional
                  :throw-entire-message? false
                  :insecure? true})

(defn endpoint [& fmt-with-args]
  (str (api-uri) (apply format fmt-with-args)))

;; helpers

(defn GET [& fmt-with-args] (client/get (apply endpoint fmt-with-args) client-opts))

;; formatters

(defn url-from-key [k]
  (str (base-uri) "/browse/" k))

(defn format-issue [issue-data]
  (let [fs (:fields issue-data)]
    [(:summary fs)
     (str "Assignee: " (-> fs :assignee :displayName))
     (str "Status: " (-> fs :status :name))
     (url-from-key (:key issue-data))]))

(defn format-issue-short [issue-data]
  (let [fs (:fields issue-data)]
    (format "[%s] [%s] %s %s"
            (or (-> fs :assignee :name) "unassigned")
            (-> fs :status :name)
            (:summary fs)
            (url-from-key (:key issue-data)))))

(defn format-comment [c]
  (str "📞 "
       (-> c :author :name) " "
       (parse-and-format-date-string (:created c))
       ": " (:body c)))

(defn format-worklog-item [w]
  (str "🚧 " (-> w :author :name) " " (:timeSpent w) ": " (:comment w)
       " [" (parse-and-format-date-string (:started w)) "]"))

(defn format-worklog-items [issue-data]
  (when-let [worklog (-> issue-data :fields :worklog :worklogs)]
    (map format-worklog-item worklog)))

(defn format-subtasks [issue-data]
  ;; TODO
  nil)

(defn format-attachment-item [a]
  (str "📎 "
       (-> a :author :name) " "
       (parse-and-format-date-string (:created a))
       ": " (:content a)))

(defn format-attachments [issue-data]
  (when-let [attachments (-> issue-data :fields :attachment)]
    (map format-attachment-item attachments)))

(defn format-issue-long
  "Show the full details for an issue"
  [issue-data]
  (let [fs (:fields issue-data)]
    (flatten
      (keep identity
            [(str (:key issue-data) " ↪︎ " (-> fs :status :name) " ↪︎ " (:summary fs))
             (:description fs)
             (s/join
               "  "
               [(str "👷 " (-> fs :assignee :name))
                (str "👮 " (-> fs :reporter :name))])
             (s/join
               " "
               [(str "❗️ Priority: " (-> fs :priority :name))
                (str " ✅ Fix version: " (s/join ", " (map :name (:fixVersions fs))))])
             (str "🕐 Created: " (parse-and-format-date-string (:created fs))
                  "  🕗 Updated: " (parse-and-format-date-string (:updated fs)))
             (map format-comment (-> fs :comment :comments))
             (format-worklog-items issue-data)
             (format-subtasks issue-data)
             (format-attachments issue-data)
             (str "👉 " (url-from-key (:key issue-data)))]))))


;; issues

(defn issue-create-meta [] (GET "/issue/createmeta"))

(defn get-transitions [i]
  (client/get (endpoint "/issue/%s/transitions?transitionId" i)
              client-opts))

(def ^:private find-resolve (partial filter #(= "Resolve Issue" (:name %))))

(defn- transition-issue [i transition-id comment]
  (let [params {:update {:comment [{:add {:body comment}}]}
                :fields {:resolution {:name "Fixed"}}
                :transition transition-id}]
    (client/post
      (endpoint "/issue/%s/transitions?transitionId" i)
      (merge client-opts
             {:form-params params :content-type :json}))))

(defn resolve-issue
  "Transition an issue to the resolved state. If it is unable to make that
   transition nil will be returned"
  [i comment]
  (let [ts (:body (get-transitions i))
        resolve-t (find-resolve (:transitions ts))]
    (when-let [t (first resolve-t)]
      (transition-issue i (:id t) comment))))

(defn post-comment [issue-key body]
  (let [uri (endpoint "/issue/%s/comment" issue-key)]
    (client/post uri
                 (merge client-opts
                        {:content-type :json
                         :form-params {:body body}}))))

(defn add-worklog-item [issue-key time-spent work-description]
  (let [uri (endpoint "/issue/%s/worklog" issue-key)
        form-params {:timeSpent time-spent
                     :comment work-description}]
    (client/post uri
                 (merge client-opts
                        {:content-type :json
                         :form-params form-params}))))
(comment

  ;; scratch space for playing with JIRA api

  (def username "_Yetibot_admin")

  (client/get (endpoint "/user/properties")
              (merge client-opts
                     {:query-params {:username username}
                      :throw-exceptions false
                      }
                     ))

  (def yetibot
    (client/get (endpoint "/user")
                (merge client-opts
                       {:query-params {:username "_Yetibot_admin"}
                        :throw-exceptions false}
                       )))

  (def updated-name "Yetibot")

  ;; these don't work /shrug
  (client/put (endpoint "/user/properties/displayName")
              (merge client-opts
                     {:query-params {:username username
                                     :value updated-name}
                      :throw-exceptions false}))
  (client/put (endpoint "/user")
              (merge client-opts
                     {:query-params {:username username
                                     :key "name"
                                     :value "Yetibot"}
                      :throw-exceptions false }))

  ;; get issue types

  )


(defn get-issue
  "Fetch json for a given JIRA"
  [i]
  (let [uri (endpoint "/issue/%s" i)
        opts (merge client-opts {:query-params {"fields" "*navigable,comment,worklog,attachment"}})]
    (try
      (client/get uri opts)
      (catch Exception e
        (info "issue not found" i)))))

(def fetch-and-format-issue-short (comp format-issue-short :body get-issue))

(defn find-project [pk]
  (try
    (:body (client/get (endpoint "/project/%s" pk) client-opts))
    (catch Exception _
      nil)))

(defn priorities []
  (client/get (endpoint "/priority") client-opts))

(defn find-priority-by-key [k]
  (let [kp (re-pattern (str "(?i)" k))]
    (first (filter #(re-find kp (:name %))
                   (:body (priorities))))))

(defn issue-types []
  (:body (client/get (endpoint "/issuetype") client-opts)))

(defn update-issue
  [issue-key {:keys [fix-version summary component-ids assignee priority-key desc timetracking]}]
  (let [pri-id (if priority-key (:id (find-priority-by-key priority-key)))
        params {:fields
                (merge
                  {}
                  (when fix-version {:fixVersions [{:name fix-version}]})
                  (when summary {:summary summary})
                  (when assignee {:assignee assignee})
                  (when component-ids {:components (map #(hash-map :id %) component-ids)})
                  (when desc {:description desc})
                  (when timetracking {:timetracking timetracking})
                  (when pri-id {:priority {:id pri-id}}))}]
    (info "update issue" (pr-str params))
    (client/put
      (endpoint "/issue/%s" issue-key)
      (merge client-opts
             {:coerce :always
              :throw-exceptions false
              :form-params params
              :content-type :json}))))

;; TODO consolidate determineing project key from context (channel settings or
;; global config)

(defn create-issue
  "This thing is a beast; thanks JIRA."
  [{:keys [summary component-ids assignee priority-key desc project-key
           fix-version timetracking issue-type-id parent]
    :or {desc "" assignee "-1"
         issue-type-id (if parent (sub-task-issue-type-id)
                         (default-issue-type-id))
         project-key (or (first *jira-projects*)
                         (default-project-key))}}]
  (info "issue-type-id" issue-type-id)
  (if-let [prj (find-project project-key)]
    (if-let [priority (if priority-key
                        (find-priority-by-key priority-key)
                        (first (:body (priorities))))]
      (let [pri-id (:id priority)
            prj-id (:id prj)
            fix-version-map (if fix-version
                              {:name fix-version}
                              (when-let [dvi (default-version-id project-key)]
                                {:id dvi}))
            params {:fields
                    (merge {:assignee {:name assignee}
                            :project {:id prj-id}
                            :summary summary
                            :components (map #(hash-map :id %) component-ids)
                            :description desc
                            :issuetype {:id issue-type-id}
                            :priority {:id pri-id}}
                           (when fix-version-map :fixVersions [fix-version-map])
                           (when timetracking {:timetracking timetracking})
                           (when parent {:parent {:id parent}}))}]
        (info "create issue" (pr-str params))
        (client/post
          (endpoint "/issue")
          (merge client-opts
                 {:form-params params
                  :content-type :json})))
      (warn "Could not find a priority for key " priority-key))
    (warn "Could not find project" project-key)))

(defn delete-issue [issue-key]
  (client/delete
    (endpoint "/issue/%s" issue-key)
    (merge client-opts {:coerce :always
                        :content-type :json
                        :throw-exceptions false})))

(defn assign-issue
  [issue-key assignee]
  (client/put
    (endpoint "/issue/%s/assignee" issue-key)
    (merge client-opts
           {:content-type :json
            :form-params {:name assignee}})))

;; versions

(defn versions
  ([] (versions (default-project-key)))
  ([project-key]
   (client/get
     (endpoint "/project/%s/versions" project-key)
     client-opts)))

;; components

(defn components [project-key]
  (client/get
    (endpoint "/project/%s/components" project-key)
    client-opts))

(def all-components
  (memo/ttl #(map components (project-keys))
            :ttl/threshold 3600000))

(defn find-component-like
  "Match components across all projects"
  [pattern-str]
  (let [re (re-pattern (str "(?i)" pattern-str))]
    (filter #(re-find re (:name %)) (mapcat :body (all-components)))))

;; users

(defn get-users [project]
  (client/get
    (endpoint "/user/assignable/multiProjectSearch")
    (merge client-opts
           {:query-params
            {"projectKeys" project}})))

;; search

(defn- projects-jql [] (str "(project in (" (project-keys-str) "))"))

(defn search [jql]
  (info "JQL search" jql)
  (client/get
    (endpoint "/search")
    (merge client-opts
           {:coerce :always
            :throw-exceptions false
            :query-params
            {:jql jql
             :startAt 0
             :maxResults (max-results)
             :fields ["summary" "status" "assignee"]}})))

(defn search-in-projects [jql]
  (search (str (projects-jql) " AND (" jql ")")))

(defn search-by-query [query]
  (search-in-projects
    (str
      "(summary ~ \"" query "\" OR description ~ \"" query
      "\" OR comment ~ \"" query "\")")))

(defn recent [] (search (projects-jql)))

;; prime cache
;; todo: move into a start fn ;; (future (all-components))
