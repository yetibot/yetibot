(ns yetibot.commands.jenkins
  (:require
    [yetibot.models.jenkins :as model]
    [clojure.string :as s]
    [yetibot.hooks :refer [cmd-hook]]))

(defn find-job-and-do [job-to-match f]
  (if-let [job (model/resolve-job-info job-to-match)]
    (f job)
    (format "I couldn't match any jobs on %s." job-to-match)))

(defn build
  "jen build <job-name>"
  [{[_ job-pattern] :match}]
  (find-job-and-do job-pattern model/build-job))

(defn list-jobs
  ([] (model/job-names))
  ([n] (take n (model/job-names))))

(defn list-jobs-matching [match]
  (let [p (re-pattern (s/trim match))]
    (filter #(re-find p %) (model/job-names))))

(defn build-default-cmd
  "jen build # build default job if configured"
  [_] (if-let [job-pair (model/default-job)]
        (model/build-job job-pair)
        "No default job configured"))

(defn status-cmd
  "jen status <job-name>       # show Jenkins status for <job-name>
   jen status                  # show Jenkins status for default job if configured"
  [{args :match}]
  (if (coll? args)
    (find-job-and-do (second args) model/job-status)
    (if-let [df (model/default-job)]
      (model/job-status df)
      "No default job configured")))

(defn list-cmd
  "jen list                    # lists all jenkins jobs
   jen list <pattern>          # lists jenkins jobs containing <string>"
  [{args :match}]
  (if (coll? args)
    (list-jobs-matching (second args))
    (list-jobs)))

(defn instances-cmd
  "jen instances # show all configured instances"
  [_]
  (into {} (for [[inst-name inst-info] (model/instances)]
             [(name inst-name) (:uri inst-info)])))

(cmd-hook #"jen"
          #"^instances$" instances-cmd
          #"^build$" build-default-cmd
          #"^build\s(\S+)\s*$" build
          #"^status$" status-cmd
          #"^status\s(.+)" status-cmd
          #"^list\s(.+)" list-cmd
          #"^list\s*$" list-cmd)
