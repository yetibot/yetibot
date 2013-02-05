(ns yetibot.commands.github
  (:require [yetibot.api.github :as gh]
            [yetibot.util.http :refer [get-json]]
            [clojure.string :as s])
  (:use [yetibot.hooks :only [cmd-hook]]
        [yetibot.util :only [env]]))

(defn feed
  "gh feed # list recent activity"
  [_] (gh/formatted-events))

(defn repos
  "gh repos # list all known repos"
  [_] (map :name (gh/repos)))

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
        (repeat "--")))

(cmd-hook ["gh" #"^gh$"]
          #"feed" feed
          #"repos" repos
          #"status" status
          #"statuses" statuses
          #"branches\s+(\S+)" branches)
