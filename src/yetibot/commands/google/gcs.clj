(ns yetibot.commands.google.gcs
  (:require
    [yetibot.api.google.gcs :as gcs]
    [clojure.string :as s]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn content-cmd
  "gsc content <path> # retrieve content of <path> from Google Cloud Storage"
  [{[_ path] :match}] (gcs/content path))

(defn buckets
  "gcs buckets # list all buckets"
  [_] (map :name (gcs/buckets)))

(defn ls
  "gcs ls <path> # list objects"
  [{[_ path] :match}]
  (let [[bucket prefix] (s/split path #"\/" 2)
        prefix (or prefix "")
        res (gcs/list-objects bucket prefix)]
    {:result/value (map (fn [{blob-name :name}]
                          (str bucket "/" blob-name)) res)
     :result/data res}))

(cmd-hook #"gcs"
  #"ls\s+(\S+)" ls
  #"buckets" buckets
  #"content\s+(\S+)" content-cmd)
