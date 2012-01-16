(ns yetibot.test.commands.jenkins
  (:require [clojure.contrib.string :as s])
  (:use [yetibot.util])
  (:use [clojure.test]))

(defn expand-cmd-hook []
  (clojure.pprint/pprint
    (macroexpand-1 '(cmd-hook
                      #"test-hook"
                      #"command" (println "run this if it matches")))))

