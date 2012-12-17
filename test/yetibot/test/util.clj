(ns yetibot.test.util
  (:require [clojure.string :as s])
  (:use [yetibot.util])
  (:use [clojure.test]))

(defn command-stub
  "command stub" []
  (println "i'm just a stub"))

(defn command-stub2
  "command stub 2" []
  (println "i'm just a stub"))

(def hook-stub `(cmd-hook
                  #"test-hook"
                  #"command" (command-stub)
                  #"other" (command-stub2)))

(defn expand-cmd-hook []
  (clojure.pprint/pprint
    (macroexpand-1 hook-stub )))

(defn run-hook []
  ~(hook-stub))
