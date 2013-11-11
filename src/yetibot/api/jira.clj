(ns yetibot.api.jira
  (:require
    [yetibot.config :refer [config-for-ns conf-valid?]]
    [yetibot.util.http :refer [get-json]]))

(def config (config-for-ns))
(def configured? (conf-valid?))

(def domain (:domain config))
(def base-uri (str "https://" domain))
(def api-uri (str base-uri "/rest/api/latest"))
(def auth (select-keys config [:user :password]))

(defn get-issue
  "Fetch json for a given JIRA"
  [i]
  (let [uri (str api-uri "/issue/" i)]
    (get-json uri auth)))
