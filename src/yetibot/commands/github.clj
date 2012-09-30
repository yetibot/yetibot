(ns yetibot.commands.github
  (:require [yetibot.api.github :as gh]
            [clojure.string :as s])
  (:use [yetibot.util :only (cmd-hook env)]))

(defn feed
  "feed # list recent activity"
  []
  (gh/formatted-events))

(defn repos
  "repos # list all known repos"
  []
  (map :name (gh/repos)))

(cmd-hook #"gh"
          #"feed" (feed)
          #"repos" (repos))
