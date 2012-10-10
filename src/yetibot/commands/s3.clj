(ns yetibot.commands.s3
  (:require [yetibot.api.s3 :as s3]
            [clojure.string :as s])
  (:use [yetibot.util :only (cmd-hook env)]))

(defn content-cmd
  "s3 content <path> # retrieve content of <path> from S3"
  [path]
  (s3/content path))

(cmd-hook #"s3"
          #"content\s+(\S+)" (content-cmd (nth p 1)))
