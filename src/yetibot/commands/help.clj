(ns yetibot.commands.help
  (:require [clojure.string :as s])
  (:use [yetibot.hooks :only [cmd-hook]]
        [yetibot.models.help :only (get-docs get-docs-for)]))

(def separator
  "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")

(defn help-topics
  [_]
  (println "fetching help topics")
  (str "These are the topics I know about. Use help <topic> for more details."
       \newline
       (s/join \newline
               (sort (keys (get-docs))))))

(defn help-for-topic
  "help <topic> # get help for <topic>"
  [{prefix :args}]
  (s/join \newline
          (or
            (seq (get-docs-for prefix))
            (list (str "I couldn't find any help for topic " prefix)))))

(defn help-all-cmd
  "help all # get help for all topics"
  [_]
  (s/join (str \newline separator \newline)
          (for [section (vals (get-docs))]
            (s/join \newline section))))

(cmd-hook #"help"
          #"all" help-all-cmd
          #"^$" help-topics
          #"^\w+$" help-for-topic)
