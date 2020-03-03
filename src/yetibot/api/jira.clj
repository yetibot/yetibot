(ns yetibot.api.jira
  (:require
   [yetibot.util :refer [oauth1-credentials]]
   [taoensso.timbre :refer [info warn error color-str]]
   [clojure.spec.alpha :as s]
   [yetibot.core.spec :as yspec]
   [clojure.string :as string]
   [clj-http.client :as client]
   [oauth.client :as oauth]
   [clojure.core.memoize :as memo]
   [yetibot.core.config :refer [get-config]]
   [yetibot.core.util.http :refer [get-json fetch]]
   [clj-time
    [local :refer [local-now]]
    [format :refer [formatter formatters show-formatters parse unparse]]]))

(s/def ::id ::yspec/non-blank-string)

(s/def ::key ::yspec/non-blank-string)
(s/def ::version (s/keys :req-un [::id]))
(s/def ::default (s/keys :req-un [::version]))
(s/def ::project (s/keys :req-un [::key]
                         :opt-un [::default]))

(s/def ::projects (s/coll-of ::project :kind vector?))

(s/def ::results ::yspec/non-blank-string)
(s/def ::max (s/keys :req-un [::results]))

(s/def ::type (s/keys :req-un [::id]))
(s/def ::issue (s/keys :req-un [::type]))
(s/def ::subtask (s/keys :req-un [::issue]))

(s/def ::default (s/keys :opt-un [::issue ::project]))

(s/def ::domain ::yspec/non-blank-string)
(s/def ::user ::yspec/non-blank-string)
(s/def ::password ::yspec/non-blank-string)

(s/def ::secret ::yspec/non-blank-string)
(s/def ::verifier ::yspec/non-blank-string)
(s/def ::token ::yspec/non-blank-string)
(s/def ::access (s/keys :req-un [::token]))

(s/def ::consumer (s/keys :req-un [::key
                                   ::secret]))

(s/def ::oauth1 (s/keys :req-un [::consumer
                                 ::verifier
                                 ::access]))

(s/def ::config (s/keys :req-un [::domain ::user]
                        :opt-un [::projects ::default ::max ::subtask
                                 ;; if password is include, basic auth is used
                                 ::password
                                 ;; if oauth is included use it instead
                                 ::oauth1]))

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
  (when-let [setting (get channel-settings jira-project-setting-key)]
    (info "channel-projects" (pr-str setting))
    (seq (remove string/blank? (string/split setting #",\s*")))))

(defn config [] (:value (get-config ::config [:jira])))

(defn configured? [] (config))

(defn projects [] (:projects (config)))

(defn project-for-key [k] (first (filter #(= (:key %) k) (projects))))

(defn project-keys [] (concat
                       (if *jira-project* [*jira-project*] [])
                       (vec *jira-projects*)
                       (map :key (projects))))

(defn project-keys-str [] (string/join "," (into
                                            (project-keys)
                                            *jira-projects*)))

(comment
  (config)
  (configured?)
  (project-keys)
  (project-keys-str))

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

(def auth (when (config)
            (map (config) [:user :password])))

;; oauth 1
;; https://developer.atlassian.com/server/jira/platform/oauth/
;; This assume that you've already gone through the OAuth dance and obtained a
;; an access token
(def authorize-url (str (base-uri) "/plugins/servlet/oauth/authorize"))
(def request-token-url (str (base-uri) "/plugins/servlet/oauth/request-token"))
(def access-token-url (str (base-uri) "/plugins/servlet/oauth/access-token"))

(def oauth1 (:oauth1 (config)))

(when (configured?) (info "✅ JIRA is configured"))

(def consumer
  (when oauth1
    (info "✅ oauth1 is configured for JIRA API access")
    (oauth/make-consumer
     (-> oauth1 :consumer :key)
     (-> oauth1 :consumer :secret)
     request-token-url
     access-token-url
     authorize-url
     :rsa-sha1)))

(when (and auth (every? identity auth))
  (info "✅ basic auth is configured for JIRA API access"))

;; formatters
;; move to yetibot.core util if anyone else needs date parsing and formatting:

(def date-time-format (:date-hour-minute formatters))
(defn parse-and-format-date-string [date-string]
  (unparse date-time-format (parse date-string)))

(defn client-opts
  "For oauth1 client-opts need to be computed for every API in order to generate
   an authz header, baking any query-params into the signature.

   http-method should be: :GET :POST :PUT or :DELETE"
  [uri http-method & [query-params]]
  ;; NOTE using :json-strict because of https://github.com/dakrone/clj-http/pull/507
  (merge {:as :json-strict
          :throw-exceptions true
          :coerce :unexceptional
          :throw-entire-message? true
          :insecure? true}
         (when query-params
           {:query-params query-params})
         (when (every? identity auth)
           {:basic-auth auth})
         (when oauth1
           (let [oauth-params (-> (oauth1-credentials
                                   consumer
                                   (-> oauth1 :access :token)
                                   (:verifier oauth1)
                                   http-method
                                   uri
                                   query-params)
                                  (dissoc :oauth_version)
                                  (assoc :oauth_verifier (:verifier oauth1)))
                 authz-header (oauth/authorization-header oauth-params)]
             {:headers {"Authorization" authz-header}}))))

(defn endpoint [& fmt-with-args]
  (str (api-uri) (apply format fmt-with-args)))

(comment
  (endpoint "/search"))

;; helpers

(defn http-get
  [uri & [{:keys [query-params] :as opts}]]
  (client/get
   uri (merge opts (client-opts uri :GET query-params))))

(defn http-post
  [uri & [{:keys [query-params] :as opts}]]
  (client/post
   uri (merge opts (client-opts uri :POST query-params))))

(defn http-put
  [uri & [{:keys [query-params] :as opts}]]
  (client/put
   uri (merge opts (client-opts uri :PUT query-params))))

(defn http-delete
  [uri & [{:keys [query-params] :as opts}]]
  (client/delete
   uri (merge opts (client-opts uri :DELETE query-params))))

;; formatters

(defn url-from-key [k]
  (str (base-uri) "/browse/" k))

(defn format-project
  [{{project-category-name :name} :projectCategory
    project-key :key
    project-name :name
    :as project}]
  (str "[" project-key "]"
       (when project-category-name
         (str " [" project-category-name "]"))
       " "
       project-name))

(defn format-issue [issue-data]
  (let [fs (:fields issue-data)]
    [(:summary fs)
     (str "Assignee: " (-> fs :assignee :displayName))
     (str "Status: " (-> fs :status :name))
     (url-from-key (:key issue-data))]))

(defn format-issue-short [issue-data]
  (let [fs (:fields issue-data)]
    (format "[%s] [%s] [%s] %s %s"
            (or (-> fs :assignee :displayName) "unassigned")
            (-> fs :status :name)
            (-> fs :issuetype :name)
            (:summary fs)
            (url-from-key (:key issue-data)))))

(defn format-comment [c]
  (str "💬 "
       (-> c :author :displayName) " "
       (parse-and-format-date-string (:created c))
       ": " (:body c)))

(defn format-worklog-item [w]
  (str "🚧 " (-> w :author :displayName) " " (:timeSpent w) ": " (:comment w)
       " [" (parse-and-format-date-string (:started w)) "]"))

(defn format-worklog-items [issue-data]
  (when-let [worklog (-> issue-data :fields :worklog :worklogs)]
    (map format-worklog-item worklog)))

(defn format-subtasks [{{subtasks :subtasks} :fields}]
  (when subtasks
    (map (fn [{st-key :key
               {summary :summary} :fields}]
           (str "➡️ "
                "[" st-key "] " summary))
         subtasks)))

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
            [(str (:key issue-data) " 🔵 " (-> fs :status :name) " 🔵 " (:summary fs))
             (:description fs)
             (string/join
               "  "
               [(str "👷 " (-> fs :assignee :displayName))
                (str "👮 " (-> fs :reporter :displayName))])
             (string/join
               " "
               [(str "❗️ Priority: " (-> fs :priority :name))
                (str " ✅ Fix version: " (string/join ", " (map :name (:fixVersions fs))))])
             (str "🕐 Created: " (parse-and-format-date-string (:created fs))
                  "  🕗 Updated: " (parse-and-format-date-string (:updated fs)))
             (map format-comment (-> fs :comment :comments))
             (format-worklog-items issue-data)
             (format-subtasks issue-data)
             (format-attachments issue-data)
             (str "👉 " (url-from-key (:key issue-data)))]))))


;; issues

(defn issue-create-meta [] (http-get (endpoint "/issue/createmeta")))

(defn get-transitions [i]
  (http-get
   (endpoint "/issue/%s/transitions" i)
   {:query-params {:transitionId nil}}))

(def ^:private find-resolve
  (partial filter #(or
                     (= "Done" (:name %))
                     (= "Resolve Issue" (:name %)))))

(defn- transition-issue [i transition-id iss-comment]
  (let [params {:update {:comment [{:add {:body iss-comment}}]}
                ;; whether resolution is present depends on the configured
                ;; screen. by default it's not present, so including this
                ;; property would trigger the error:
                ;; {"errorMessages":[],"errors":{"resolution":"Field 'resolution' cannot be set. It is not on the appropriate screen, or unknown."}}
                ;; TODO dynamically determine if `resolution` is configured
                ;; /shrug
                ;; :fields {:resolution {:name "Fixed"}}
                :transition {:id transition-id}}]
    (http-post
     (endpoint "/issue/%s/transitions" i)
     {:form-params params
      :content-type :json})))

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
    (http-post uri
               {:content-type :json
                :form-params {:body body}})))

(defn add-worklog-item [issue-key time-spent work-description]
  (let [uri (endpoint "/issue/%s/worklog" issue-key)
        form-params {:timeSpent time-spent
                     :comment work-description}]
    (http-post uri
               {:content-type :json
                :form-params form-params})))

(comment
  ;; this is HUGE - it will freez your REPL if you try it
  #_(issue-create-meta)

  (def iss-key (-> (recent) :body :issues first :key))

  (add-worklog-item
   iss-key
   "2d"
   "jira oauth1 nightmare")
  (post-comment iss-key "will it ever end")

  (get-transitions iss-key)
  (->> (get-transitions iss-key)
       :body
       :transitions
       (map :name))

  (get-transitions "COM-3")

  (-> iss-key
      get-transitions
      :body
      :transitions
      find-resolve)

  (resolve-issue iss-key "do you even resolve")

  *e)


(defn get-issue
  "Fetch json for a given JIRA"
  [i]
  (let [uri (endpoint "/issue/%s" i)
        opts {:query-params {:fields "*navigable,comment,worklog,attachment"}}]
    (http-get uri opts)
    #_(try
      (http-get uri opts)
      (catch Exception e
        (info "issue not found" i)))))

(comment
  (get-issue "YETIBOT-5")
  *e)

(def fetch-and-format-issue-short (comp format-issue-short :body get-issue))

(defn find-project [pk]
  (try
    (:body (http-get (endpoint "/project/%s" pk)))
    (catch Exception e
      (info "unable to find project:" e)
      nil)))

(comment
  (find-project "YETIBOT"))

(defn priorities []
  (http-get (endpoint "/priority")))

(defn find-priority-by-key [k]
  (let [kp (re-pattern (str "(?i)" k))]
    (first (filter #(re-find kp (:name %))
                   (:body (priorities))))))

(defn issue-types []
  (:body (http-get (endpoint "/issuetype"))))

(defn update-issue
  [issue-key {:keys [fix-version summary component-ids reporter assignee
                     priority-key desc timetracking]}]
  (let [pri-id (if priority-key (:id (find-priority-by-key priority-key)))
        params {:fields
                (merge
                 {}
                 (when fix-version {:fixVersions [{:name fix-version}]})
                 (when summary {:summary summary})
                 (when assignee {:assignee assignee})
                 (when reporter {:reporter reporter})
                 (when component-ids {:components (map #(hash-map :id %) component-ids)})
                 (when desc {:description desc})
                 (when timetracking {:timetracking timetracking})
                 (when pri-id {:priority {:id pri-id}}))}]
    (info "update issue" (pr-str params))
    (http-put
     (endpoint "/issue/%s" issue-key)
     {:coerce :always
      :throw-exceptions false
      :form-params params
      :content-type :json})))

(comment

  (priorities)
  (issue-types)
  *e
  (update-issue
   (-> (recent) :body :issues first :key)
   {:desc (str (local-now))}))

;; TODO consolidate determineing project key from context (channel settings or
;; global config). currently we duplicate that logic in many places.

(defn create-issue
  "This thing is a beast; thanks JIRA."
  [{:keys [summary component-ids reporter assignee priority-key desc project-key
           fix-version timetracking issue-type-id parent]
    :or {desc "" assignee "-1"
         issue-type-id (if parent (sub-task-issue-type-id)
                           (default-issue-type-id))
         project-key (or (first *jira-projects*)
                         (default-project-key))}}]
  (info "create-issue"
        (color-str :blue {:issue-type-id issue-type-id
                          :project-key project-key
                          :parent parent
                          :assignee assignee
                          :component-ids component-ids}))
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
            _ (info "fix-version-map" fix-version-map)
            params {:fields
                    (merge {:assignee {:name assignee}
                            :project {:id prj-id}
                            :summary summary
                            :description desc
                            :issuetype {:id issue-type-id}
                            :priority {:id pri-id}}
                           (when component-ids
                             {:components (map #(hash-map :id %)
                                               component-ids)})
                           (when reporter
                             {:reporter {:name reporter}})
                           (when fix-version-map
                             {:fixVersions [fix-version-map]})
                           (when timetracking
                             {:timetracking timetracking})
                           (when parent
                             {:parent {:key parent}}))}]
        (info "create issue" (pr-str params))
        (http-post
         (endpoint "/issue")
         {:form-params params
          :content-type :json}))
      (warn "Could not find a priority for key " priority-key))
    (warn "Could not find project" project-key)))

(comment
  (create-issue {:summary "test issue creation"})
  (default-version-id "YETIBOT")
  *e)

(defn delete-issue [issue-key]
  (http-delete
   (endpoint "/issue/%s" issue-key)
   {:coerce :always
    :content-type :json
    :throw-exceptions false}))

(comment
  ;; delete random issue
  (let [issue (-> (recent) :body :issues rand-nth :key)]
    (info "deleting issue" issue)
    (delete-issue issue)))

(defn assign-issue
  [issue-key assignee-user-id]
  (http-put
    (endpoint "/issue/%s/assignee" issue-key)
   {:content-type :json
    :form-params {:accountId assignee-user-id}}))

(comment
  ;; assign the most recent issue for the default project to a random user
  (let [user (-> (default-project-key) get-users :body rand-nth)
        issue (-> (recent) :body :issues first :key)]
    (info {:user user :issue issue})
    (assign-issue issue (:accountId user)))

  *e)

;; projects

(comment
  ;; list projects
  (http-get
   (endpoint "/project/"))
  )

;; versions

(defn versions
  ([] (versions (default-project-key)))
  ([project-key]
   (http-get
     (endpoint "/project/%s/versions" project-key))))

(comment (versions))

;; components

(defn components [project-key]
  (http-get
   (endpoint "/project/%s/components" project-key)))

(def all-components
  (memo/ttl #(map components (project-keys))
            :ttl/threshold 3600000))

(defn find-component-like
  "Match components across all projects"
  [pattern-str]
  (let [re (re-pattern (str "(?i)" pattern-str))]
    (filter #(re-find re (:name %)) (mapcat :body (all-components)))))

(comment
  (components (first (project-keys)))
  (find-component-like "bugs"))

;; users

(defn get-users [project]
  (let [uri (endpoint "/user/assignable/multiProjectSearch")]
    (http-get
     uri
     {:query-params {:projectKeys project}})))

(defn search-users
  "Find a user entity matching against display name and email.

   query - A query string that is matched against user attributes ( displayName,
   and emailAddress) to find relevant users. The string can match the prefix of
   the attribute's value. For example, query=john matches a user with a
   displayName of John Smith and a user with an emailAddress of
   johnson@example.com"
  [query]
  (http-get
   (endpoint "/user/search")
   {:query-params
    (merge {:query query
            :username query})}))

(comment
  (search-users "y")
  (search-users "trevor")
  (get-users (first (project-keys))))

;; (defn find-user-assignable-to
;;   [issue-key & [user-to-search-for]]
;;   (http-get
;;     (endpoint "/user/assignable/search")
;;     {:query-params
;;      (merge {:issueKey issue-key}
;;             (when user-to-search-for {}))}))


;; projects

(defn get-projects [& [query]]
  (http-get
    (endpoint "/project/search")
    {:query-params (merge {}
                          (when query {:query query}))}))

(comment
  (get-projects)
  )

;; search

(defn- projects-jql [& [project]]
  (if project
    (str "(project in (" project "))")
    (str "(project in (" (project-keys-str) "))")))

(defn search [jql]
  (info "JQL search" jql)
  (http-get
   (endpoint "/search")
   {:query-params {:jql jql
                   :startAt 0
                   :maxResults (max-results)
                   :fields "summary,issuetype,status,assignee"}
    :coerce :always
    :throw-exceptions false}))

(defn search-in-projects [jql]
  (search (str (projects-jql) " AND (" jql ")")))

(defn search-by-query [query]
  (search-in-projects
    (str
      "(summary ~ \"" query "\" OR description ~ \"" query
      "\" OR comment ~ \"" query "\")")))

(defn recent [& [project]]
  (search
   (str (projects-jql project) " ORDER BY updatedDate")))

(comment
  (search-by-query "demo")
  (projects-jql)
  (projects-jql "FOO")
  (search "created >= -5h")
  (recent)
  (recent "YETIBOT")
  *e
  )

;; prime cache
;; todo: move into a start fn ;; (future (all-components))

(comment
  ;; scratch space for playing with JIRA api
  (def username "_Yetibot_admin")
  (http-get (endpoint "/user/properties"))
  (endpoint "/user")

  (http-get (endpoint "/user"))
  *e

  (def updated-name "Yetibot")
  ;; these don't work /shrug
  (http-put (endpoint "/user/properties/displayName")
            {:query-params {:username username
                            :value updated-name}
             :throw-exceptions false})
  (http-put (endpoint "/user")
            {:query-params {:username username
                            :key "name"
                            :value "Yetibot"}
             :throw-exceptions false}))
