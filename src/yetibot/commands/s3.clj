(ns yetibot.commands.s3
  (:require [yetibot.api.s3 :as s3]
            [clojure.string :as s])
  (:use [yetibot.hooks :only [cmd-hook]]
        [yetibot.util :only [env]]))

(defn content-cmd
  "s3 content <path> # retrieve content of <path> from S3"
  [{[_ path] :match}]
  (s3/content path))

(cmd-hook #"s3"
          #"content\s+(\S+)" content-cmd)
