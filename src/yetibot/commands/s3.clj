(ns yetibot.commands.s3
  (:require [yetibot.api.s3 :as s3]
            [clojure.string :as s])
  (:use [yetibot.hooks :only [cmd-hook]]
        [yetibot.util :only [env]]))

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
        res (if prefix
              (s3/list-objects bucket {:delimiter "/" :prefix prefix})
              (s3/list-objects bucket))]
    (map #(format "%s/%s" bucket %)
         (concat (:common-prefixes res) (map :key (:objects res))))))

(cmd-hook #"s3"
          #"ls\s+(\S+)" ls
          #"buckets" buckets
          #"content\s+(\S+)" content-cmd)
