(ns yetibot.commands.help
  (:require [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [yetibot.campfire :as cf]
            [clojure.contrib.string :as s]
            [clojure.data.json :as json])
  (:use [yetibot.util :only (cmd-hook)]
        [yetibot.help :only (get-docs get-docs-for)]))


(defn get-help-topics
  "# get help topics"
  []
  (println "fetching help topics")
  (cf/send-message (s/join \newline
    (keys (get-docs)))))



(cmd-hook #"help"
          #"^$" (get-help-topics))
