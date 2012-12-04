(ns yetibot.api.github
  (:require [tentacles [users :as u]
                       [repos :as r]
                       [events :as e]
                       [data :as data]
                       [orgs :as o]]
            [clojure.string :as s]
            [clj-http.client :as client])
  (:use [yetibot.util :only (env)]
        [yetibot.util.http :only (fetch)]))


;;; uses tentacles for most api calls, but falls back to raw REST calls when
;;; tentacles doesn't support something (like Accept headers for raw blob content).
(def endpoint "https://api.github.com/")



;;; config

(def token (:GITHUB_TOKEN env))
(def auth {:oauth-token token})
(def user (u/me auth))

(def user-name (:login user))
(def org-name (:GITHUB_ORG env))

(def org (first (filter
                  #(= (:login %) org-name)
                  (o/orgs auth))))


;;; data

(defn tree
  [repo & [opts]]
  (data/tree org-name repo (or (:branch opts) "master")
          (merge auth {:recursive true} opts)))

(defn find-paths [tr pattern]
  (filter #(re-find pattern (:path %)) (:tree tr)))

(defn raw
  "Retrieve raw contents from GitHub"
  [repo path & [{:keys [branch]}]]
  (let [git-ref (or branch "master")]
    (let [uri (format (str endpoint "/repos/%s/%s/contents/%s?ref=%s") org-name repo path git-ref)]
      (client/get uri
                  {:accept "application/vnd.github.raw+json"
                  :headers {"Authorization" (str "token " token)}}))))

(defn changed-files
  "Retrieves a list of the filenames which have changed in a single commit, or between two commits"
  [repo sha1 & [sha2]]
  (let [uri (if sha2
          (format (str endpoint "/repos/%s/%s/compare/%s...%s") org-name repo sha1 sha2)
          (format (str endpoint "/repos/%s/%s/commits/%s") org-name repo sha1))
        raw-data (client/get uri {:headers {"Authorization" (str "token " token)}})
        raw-data-body (:body raw-data)
        json-data (clojure.data.json/read-json raw-data-body)]
    (map :filename (:files json-data))))

(defn was-file-changed?
  "Determines if a given file (with path) was changed in a single commit, or between two commits"
  [filename repo sha1 & [sha2]]
  (boolean (some #{filename} (changed-files repo sha1 sha2))))

;;; (client/get "http://site.com/resources/3" {:accept :json})



  ;;; ([repo path git-ref]
  ;;;  (let [uri (format "https://raw.github.com/%s/%s/%s/%s?login=%s&token=%s"
  ;;;                    org-name repo git-ref path user-name token)]
  ;;;    (prn uri)
  ;;;    (fetch uri))))


;;; repos

(defn repos []
  (r/org-repos org-name auth))

(defn branches [repo]
  (r/branches org-name repo auth))


;;; (defn contents [repo path]
;;;   (r/contents org-name repo path auth))



;;; events / feed

(defmulti fmt-event :type)

(defmethod fmt-event "PushEvent" [e]
  (str
    (-> e :actor :login)
    " pushed to "
    (-> e :payload :ref)))

(defmethod fmt-event :default [e]
  (s/join " "
          [(-> e :actor :login)
           (:type e)
           (:payload e)]))

(defn fmt-events
  [evts]
  (map fmt-event evts))

(defn events []
  (e/org-events user-name org-name auth))

(defn formatted-events []
  (fmt-events (events)))


