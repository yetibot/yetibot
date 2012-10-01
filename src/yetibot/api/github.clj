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
;;; (data/blob org-name "com.decide.website" "a58183bd07e357f769782d24a01e6d05fa84d2a0" auth)

(defn tree
  [repo]
  (data/tree org-name repo "master"
          (merge auth {:recursive true})))

(defn find-paths [tr pattern]
  (filter #(re-find pattern (:path %)) (:tree tr)))

(defn raw
  "Retrieve raw contents from GitHub"
  ([repo path] (raw repo path "master"))
  ([repo path git-ref]
   (let [uri (format (str endpoint "/repos/%s/%s/contents/%s") org-name repo path)]
     (client/get uri
                 {:accept "application/vnd.github.raw+json"
                  :headers {"Authorization" (str "token " token)}}))))


;;; (client/get "http://site.com/resources/3" {:accept :json})



  ;;; ([repo path git-ref]
  ;;;  (let [uri (format "https://raw.github.com/%s/%s/%s/%s?login=%s&token=%s"
  ;;;                    org-name repo git-ref path user-name token)]
  ;;;    (prn uri)
  ;;;    (fetch uri))))


;;; repos

(defn repos []
  (r/org-repos org-name auth))


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


