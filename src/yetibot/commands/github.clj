(ns yetibot.commands.github
  (:require [yetibot.api.github :as gh]
            [clojure.string :as s])
  (:use [yetibot.util :only (cmd-hook env)]))

(defn feed
  "gh feed # list recent activity"
  []
  (gh/formatted-events))

(defn repos
  "gh repos # list all known repos"
  []
  (map :name (gh/repos)))

(defn branches
  "gh branches <repo> # list branches for <repo>"
  [repo]
  (map :name (gh/branches repo)))

(cmd-hook #"gh"
          #"feed" (feed)
          #"repos" (repos)
          #"branches\s+(\S+)" (apply branches (rest p)))
