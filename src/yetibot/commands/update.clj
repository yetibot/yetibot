(ns yetibot.commands.update
  (:require [clojure.java.shell :as shell]
            [clojure.string :as s]
            [yetibot.hooks :refer [cmd-hook]]))

(def gpr "git pull --rebase")
(def log "git log --oneline @{1}..")
(def up-to-date-msg "Current branch master is up to date.\n")

(defn sh [c]
  (apply shell/sh (s/split c #"\s")))

(defn update-cmd
  "update # update YetiBot's local git repo"
  [_]
  (let [pull (sh gpr)]
    (if (= up-to-date-msg (:out pull))
      up-to-date-msg
      (:out (sh log)))))

(cmd-hook #"update"
          _ update-cmd)
