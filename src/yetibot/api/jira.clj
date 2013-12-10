(ns yetibot.api.jira
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :as s]
    [clj-http.client :as client]
    [yetibot.config :refer [config-for-ns conf-valid?]]
    [yetibot.util.http :refer [get-json fetch]]))

(def config (config-for-ns))
(def configured? (conf-valid?))
(defn project-keys-str [] (->> config :project-keys (s/join ",")))

(def ^:private base-uri (str "https://" (:domain config)))
(def ^:private api-uri (str base-uri "/rest/api/latest"))
(def ^:private auth (map config [:user :password]))
(def ^:private client-opts {:as :json
                            :basic-auth auth
                            :throw-entire-message? true
                            :insecure? true})

(defn endpoint [& fmt-with-args]
  (str api-uri (apply format fmt-with-args)))

;; formatters

(defn url-from-key [k]
  (str base-uri "/browse/" k))

(defn format-issue [issue-data]
  (let [fs (:fields issue-data)]
    [(-> fs :summary)
     (str "Assignee: " (-> fs :assignee :displayName))
     (str "Status: " (-> fs :status :name))
     (url-from-key (:key issue-data))]))

(defn format-issue-short [issue-data]
  (let [fs (:fields issue-data)]
    (format "%s: %s %s"
            (-> fs :status :name)
            (-> fs :summary)
            (url-from-key (:key issue-data)))))

;; issues

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

(defn get-issue
  "Fetch json for a given JIRA"
  [i]
  (let [uri (endpoint "/issue/%s" i)]
    (try
      (:body (client/get uri client-opts))
      (catch Exception _ nil))))

(defn find-project [pk]
  (try
    (:body (client/get (endpoint "/project/%s" pk) client-opts))
    (catch Exception _
      nil)))

(defn priorities []
  (:body (client/get (endpoint "/priority") client-opts)))

(defn find-priority-by-key [k]
  (let [kp (re-pattern (str "(?i)" k))]
    (first (filter #(re-find kp (:name %)) (priorities)))))

(defn issue-types []
  (:body (client/get (endpoint "/issuetype") client-opts)))

(defn create-issue
  "This thing is a beast"
  [{:keys [summary assignee priority-key desc project-key]
    :or {desc "" assignee "-1"
         project-key (first (:project-keys config))}}]
  (if-let [prj (find-project project-key)]
    (if-let [priority (if priority-key
                        (find-priority-by-key priority-key)
                        (first (priorities)))]
      (let [pri-id (:id priority)
            prj-id (:id prj)
            params {:fields
                    {:assignee {:name assignee}
                     :project {:id prj-id}
                     :summary summary
                     :description desc
                     :issuetype {:id (:default-issue-type-id config)}
                     :priority {:id pri-id}}}]
        (client/post
          (endpoint "/issue")
          (merge client-opts
                 {:coerce :always
                  :throw-exceptions false
                  :form-params params
                  :content-type :json})))
      (warn "Could not find a priority for key " priority-key))
    (warn "Could not find project" project-key)))

;; users

(defn get-users []
  (:body
    (client/get
      (endpoint "/user/assignable/multiProjectSearch")
      (merge client-opts
             {:query-params
              {"projectKeys" (project-keys-str)}}))))


;; search

(defn- projects-jql [] (str "(project in (" (project-keys-str) "))"))

(defn search [jql]
  (info "JQL search" jql)
  (client/get
    (endpoint "/search")
    (merge client-opts
           {:query-params
            {:jql jql
             :startAt 0
             :maxResults 5
             :fields ["summary" "status" "assignee"]}})))

(defn search-by-query [query]
  (search
    (str (projects-jql)
         " AND (summary ~ \"" query "\" OR description ~ \"" query
         "\" OR comment ~ \"" query "\")")))

(defn recent [] (search (projects-jql)))
