(ns yetibot.commands.github
  (:require
    [yetibot.api.github :as gh]
    [yetibot.util.http :refer [get-json]]
    [clojure.string :as s]
    [yetibot.hooks :refer [cmd-hook]]
    [taoensso.timbre :refer [info]]
    [yetibot.util :refer [env]]))

(defn feed
  "gh feed # list recent activity"
  [_] (gh/formatted-events))

(defn repos
  "gh repos # list all known repos"
  [_] (map :name (gh/repos)))

(defn repos-urls
  "gh repos urls # list the ssh urls of all repos"
  [_] (map :ssh_url (gh/repos)))

(defn tags
  "gh tags <repo> # list the tags for <repo>"
  [{[_ repo] :match}]
  (map :name (gh/tags repo)))

(defn branches
  "gh branches <repo> # list branches for <repo>"
  [{[_ repo] :match}]
  (map :name (gh/branches repo)))

(defn- fmt-status [st] ((juxt :status :body :created_on) st))

(defn status
  "gh status # show GitHub's current system status"
  [_] (fmt-status (get-json "https://status.github.com/api/last-message.json")))

(defn statuses
  "gh statuses # show all recent GitHub system status messages"
  [_] (interleave
        (map fmt-status (get-json "https://status.github.com/api/messages.json"))
        (repeat ["--"])))

(if gh/configured?
  (cmd-hook ["gh" #"^gh|github$"]
            #"feed" feed
            #"repos urls" repos-urls
            #"repos" repos
            #"statuses" statuses
            #"status$" status
            #"tags\s+(\S+)" tags
            #"branches\s+(\S+)" branches)
  (info "GitHub is not configured"))
