(ns yetibot.commands.s3
  (:require
    [yetibot.api.s3 :as s3]
    [clojure.string :as s]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn content-cmd
  "s3 content <path> # retrieve content of <path> from S3"
  [{[_ path] :match}] (s3/content path))

(defn buckets
  "s3 buckets # list all buckets"
  [_] (map :name (s3/buckets)))

(defn ls
  "s3 ls <path> # list objects"
  [{[_ path] :match}]
  (let [[bucket prefix] (s/split path #"\/" 2)
        res (s3/list-objects bucket prefix)]
    (map #(format "%s/%s" bucket %)
         (concat (:common-prefixes res) (map :key (:objects res))))))

(if s3/configured?
  (cmd-hook #"s3"
            #"ls\s+(\S+)" ls
            #"buckets" buckets
            #"content\s+(\S+)" content-cmd)
  (info "S3 is not configured"))
