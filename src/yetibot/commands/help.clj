(ns yetibot.commands.help
  (:require [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [yetibot.campfire :as cf]
            [clojure.contrib.string :as s]
            [clojure.data.json :as json])
  (:use [yetibot.util :only (cmd-hook)]
        [yetibot.help :only (get-docs get-docs-for)]))


(defn help-topics
  []
  (println "fetching help topics")
  (cf/send-message
    (str "These are the topics I know about. Use help <topic> for more details."
         \newline
         (s/join \newline
                 (keys (get-docs))))))

(defn help-for-topic
  "help <topic>                # get help for <topic>"
  [prefix]
  (cf/send-paste
    (s/join \newline
            (or
              (seq (get-docs-for prefix))
              (list (str "I couldn't find any help for topic " prefix))))))


(cmd-hook #"help"
          #"^$" (help-topics)
          #"^\w+$" (help-for-topic p))
