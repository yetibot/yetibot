(ns yetibot.api.jira
  (:use [yetibot.util.http]))

(def domain (System/getenv "JIRA_DOMAIN"))
(def base-uri (str "https://" domain))
(def api-uri (str base-uri "/rest/api/latest"))
(def auth {:user (System/getenv "JIRA_USER")
           :password (System/getenv "JIRA_PASSWORD")})

(defn get-issue
  "Fetch json for a given JIRA"
  [i]
  (let [uri (str api-uri "/issue/" i)]
    (prn uri)
    (get-json uri auth)))

(defn user-search
  "Search users"
  [username]
  ;; /user/search
  (let [uri (str api-uri "/user/search?"
                 (map-to-query-string {:username username}))]
    (prn uri)
    (get-json uri auth)))

(defn server-info
  "Returns general information about the current JIRA server"
  []
  (get-json (str api-uri "/serverInfo")))

(defn my-permissions
  "Returns all permissions in the system and whether the currently logged in user has
  them"
  []
  (get-json (str api-uri "/mypermissions")))

