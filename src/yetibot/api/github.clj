(ns yetibot.api.github
  (:require
    [tentacles
     [search :as search]
     [pulls :as pulls]
     [issues :as issues]
     [users :as u]
     [repos :as r]
     [events :as e]
     [data :as data]
     [orgs :as o]]
    [clojure.string :as s]
    [clj-http.client :as client]
    [yetibot.core.config :refer [get-config conf-valid?]]
    [yetibot.core.util.http :refer [fetch]]))


;;; uses tentacles for most api calls, but falls back to raw REST calls when
;;; tentacles doesn't support something (like Accept headers for raw blob
;;; content).

;;; config

(defn config [] (get-config :yetibot :api :github))
(def configured? (conf-valid?))
(def endpoint (or (:endpoint (config)) "https://api.github.com/"))

; propogate the configured endpoint to the tentacles library

(alter-var-root #'tentacles.core/url (constantly endpoint))

(def token (:token (config)))
(def auth {:oauth-token token})
(future
  (def user (u/me auth))
  (def user-name (:login user)))

; ensure org-names is a sequence; config allows either
(def org-names
  (let [c (:org (config))]
    (if (sequential? c) c [c])))

; (def org (first (filter
;                   #(= (:login %) org-name)
;                   (o/orgs auth))))

;;; data

(defn tree
  [org-name repo & [opts]]
  (data/tree org-name repo (or (:branch opts) "master")
          (merge auth {:recursive true} opts)))

(defn find-paths [tr pattern]
  (filter #(re-find pattern (:path %)) (:tree tr)))

(defn raw
  "Retrieve raw contents from GitHub"
  [org-name repo path & [{:keys [branch]}]]
  (let [git-ref (or branch "master")]
    (let [uri (format (str endpoint "/repos/%s/%s/contents/%s?ref=%s") org-name repo path git-ref)]
      (client/get uri
                  {:accept "application/vnd.github.raw+json"
                  :headers {"Authorization" (str "token " token)}}))))

(defn changed-files
  "Retrieves a list of the filenames which have changed in a single commit, or between two commits"
  [org-name repo sha1 & [sha2]]
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

;;; repos

(defn repos [org-name]
  (remove empty? (r/org-repos org-name (merge auth {:per-page 100}))))

(defn repos-by-org []
  (into {} (for [org-name org-names]
             [org-name (repos org-name)])))

(defn branches [org-name repo]
  (r/branches org-name repo auth))

(defn tags [org-name repo]
  (r/tags org-name repo auth))

(defn pulls [org-name repo]
  (pulls/pulls org-name repo auth))

;;; (defn contents [repo path]
;;;   (r/contents org-name repo path auth))

(defn org-issues [org-name]
  (issues/org-issues org-name auth))

;; search

(defn search-pull-requests [org-name keywords & [opts]]
  (search/search-issues keywords
                        (merge {:state "open" :type "pr" :user org-name} opts)
                        (merge {:sort "created"} auth)))


;;; events / feed

(defmulti fmt-event :type)

(defmethod fmt-event "PushEvent" [e]
  (into [(str (-> e :actor :login)
              " pushed to "
              (s/replace (-> e :payload :ref) "refs/heads/" "")
              " at "
              (-> e :repo :name))]
        (map (fn [{:keys [author sha message]}]
               (str "* "
                    (apply str (take 7 sha))
                    " "
                    message
                    " [" (:name author) "]"))
             (-> e :payload :commits))))

(defmethod fmt-event :default [e]
  (s/join " "
          [(-> e :actor :login)
           (:type e)
           (:payload e)]))

(defn fmt-events
  [evts]
  (map fmt-event evts))

(defn events [org-name]
  (e/org-events user-name org-name auth))

(defn formatted-events [org-name]
  (fmt-events (events org-name)))

