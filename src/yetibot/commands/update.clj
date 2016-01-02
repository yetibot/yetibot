(ns yetibot.commands.update
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.java.shell :as shell]
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def gpr "git pull --rebase")
(def log "git log --oneline @{1}..")
(def up-to-date-msg "Current branch master is up to date.\n")
(def default-err-msg "Error in git pull, try again")

(defn sh [c]
  (apply shell/sh (s/split c #"\s")))

(defn- handle-err [pull]
  (if (s/blank? (:err pull))
    default-err-msg
    (:err pull)))

(defn- handle-succ [pull]
  (if (= up-to-date-msg (:out pull))
    up-to-date-msg
    (:out (sh log))))

(defn update-cmd
  "update # update Yetibot's local git repo"
  {:yb/cat #{:util}}
  [_]
  (let [pull (sh gpr)
        err? (= 1 (:exit pull))]
    (info pull)
    ((if err? handle-err handle-succ) pull)))

(cmd-hook #"update"
          _ update-cmd)
