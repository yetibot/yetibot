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
    (get-json uri auth)))
