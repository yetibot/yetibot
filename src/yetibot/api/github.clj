(ns yetibot.api.github
  (:require
    [taoensso.timbre :refer [info warn error]]
    [schema.core :as sch]
    [yetibot.core.schema :refer [non-empty-str]]
    [clojure.data.csv :as csv]
    [clojure.java.io :as io]
    [tentacles
     [core :refer [with-url]]
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
    [graphql-query.core :refer [graphql-query]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util.http :refer [encode]]))

;;; uses tentacles for most api calls, but falls back to raw REST calls when
;;; tentacles doesn't support something (like Accept headers for raw blob
;;; content).

;;; config

(def github-schema
  {:token non-empty-str
   :org [non-empty-str]
   (sch/optional-key :graphql) {:endpoint non-empty-str}
   (sch/optional-key :endpoint) non-empty-str})

(defn config [] (:value (get-config github-schema [:github])))
(defn configured? [] (config))
(def endpoint (or (:endpoint (config)) "https://api.github.com/"))

(def token (:token (config)))
(def auth {:oauth-token token})
(future
  (def user (with-url endpoint (u/me auth)))
  (def user-name (:login user)))

; ensure org-names is a sequence; config allows either
(defn org-names []
  (let [c (:org (config))]
    (if (sequential? c) c [c])))

; (def org (first (filter
;                   #(= (:login %) org-name)
;                   (o/orgs auth))))


;; graphql examples

(defn contrib-query [username]
  (str "query {
      user(login: \\\"" username "\\\") {
        login
        repositoriesContributedTo(first: 100,
                                  includeUserRepositories: false,
                                  contributionTypes: [COMMIT, PULL_REQUEST]) {
          totalCount
          nodes {
            nameWithOwner
            owner {
              ... on Organization {
                name
              }
            }
          }
        }
      }
    }"))

(defn graphql [query]
  (client/post
    (-> (config) :graphql :endpoint)
    {:headers {"Authorization" (str "bearer " (:token (config)))}
     :body (str "{\"query\": \"" (s/replace query #"\n" "") "\"}")
     :as :json}))

(defn email->username [email]
  (first (s/split email #"\@")))

;;; data

(defn tree
  [org-name repo & [opts]]
  (with-url endpoint
    (data/tree org-name repo (or (:branch opts) "master")
              (merge auth {:recursive true} opts))))

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
  (with-url
    endpoint
    (r/org-repos
      org-name (merge auth {:per-page 100}))))

(comment

  (with-url
    endpoint
    (r/org-repos
      "yetibot" (merge auth {:per-page 100})))

  )


(defn repos-by-org []
  (into {} (for [org-name (org-names)]
             [org-name (repos org-name)])))

(defn branches [org-name repo]
  (with-url endpoint
    (r/branches org-name repo auth)))

(defn tags [org-name repo]
  (with-url endpoint
    (r/tags org-name repo auth)))

(defn pulls [org-name repo]
  (with-url endpoint
    (pulls/pulls org-name repo auth)))

(defn contributor-statistics [org-name repo]
  (with-url endpoint
    (r/contributor-statistics org-name repo auth)))

(defn code-frequency [org-name repo]
  (with-url endpoint
    (r/code-frequency org-name repo auth)))

(defn latest-releases [org-name repo]
  (with-url endpoint
    (r/specific-release org-name repo "latest" auth)))

(defn release-by-tag [org-name repo tag]
  (with-url endpoint
    (r/specific-release-by-tag org-name repo tag auth)))

(defn releases [org-name repo]
  (with-url endpoint
    (r/releases org-name repo auth)))

(defn sum-weekly
  "Takes the weekly stats for an author and sums them into:
   {:a 0 :d 0 :c 0}
   where:
   a = additions
   d = deletions
   con = contributors
   c = commits"
  [weekly-for-author]
  (select-keys (reduce
                 (partial merge-with +)
                 {:a 0 :d 0 :c 0 :con 1}
                 weekly-for-author)
               [:a :d :c :con 1]))

(defn sum-stats [org-name repo]
  (info "sum-stats" org-name repo)
  (let [weekly-by-author (contributor-statistics org-name repo)]
    (if (coll? weekly-by-author)
      (reduce
        (fn [acc {:keys [weeks]}] (merge-with + acc (sum-weekly weeks)))
        {:a 0 :c 0 :d 0 :con 0}
        weekly-by-author)
      weekly-by-author)))

(defn filter-since-ts
  "Stats is a collection of statistics with a :w timestamp key.
   If ts is nil return the stats coll as-is"
  [stats ts]
  (if ts
    (filter (fn [stat]
              ;; gh timestamps are seconds and joda is milliseconds
              ;; so we must multiply by 1000
              (>= (* 1000 (:w stat)) ts))
            stats)
    stats))

(defn contributors-since-ts
  "If ts is nil it gets stats for max time (52 weeks)"
  [org repo ts]
  (let [stats (contributor-statistics org repo)]
    (->> stats
         (map (fn [contrib-stat]
                (let [filtered (filter-since-ts (:weeks contrib-stat) ts)]
                  (merge
                    (select-keys (sum-weekly filtered) [:a :d :c])
                    {:author (-> contrib-stat :author :login)}))))
         (filter #(pos? (:c %)))
         (sort-by :c)
         reverse)))

;;; (defn contents [repo path]
;;;   (r/contents org-name repo path auth))

(defn org-issues [org-name]
  (with-url endpoint
    (issues/org-issues org-name auth)))

;; search

(defn search-pull-requests [org-name keywords & [opts]]
  (with-url endpoint
    (search/search-issues keywords
                          (merge {:state "open" :type "pr" :user org-name} opts)
                          (merge {:sort "created"} auth))))

(defn search-code [keywords & [query opts]]
  (with-url endpoint
            (search/search-code
              keywords (merge {} query) auth)))

(comment

  (search-pull-requests "yetibot" "")

  (search-code "org:yetibot cmd-hook")

  )

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
                    (s/join (take 7 sha))
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
  (with-url endpoint (e/org-events user-name org-name auth)))

(defn formatted-events [org-name]
  (fmt-events (events org-name)))
