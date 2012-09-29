(ns yetibot.commands.github
  (:require [tentacles [users :as u]
                       [repos :as r]
                       [events :as e]
                       [orgs :as o]]
            [clojure.string :as s])
  (:use [yetibot.util :only (cmd-hook env)]))

(def token (:GITHUB_TOKEN env))
(def auth {:oauth-token token})
(def user (u/me auth))

(def org-name (:GITHUB_ORG env))

(def org (first (filter
                  #(= (:login %) org-name)
                  (o/orgs auth))))



(defn repos
  "repos # list all known repos"
  []
  (r/repos auth))


; events / feed

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

(defn events
  []
  (e/org-events (:login user) org-name auth))

(defn feed
  "feed # list recent activity"
  []
  (fmt-events (events)))

(cmd-hook #"gh"
          #"feed" (feed))
