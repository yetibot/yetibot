(ns yetibot.commands.github
  (:require [yetibot.api.github :as gh]
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

(cmd-hook ["gh" #"^gh$"]
          #"feed" feed
          #"repos" repos
          #"branches\s+(\S+)" branches)
