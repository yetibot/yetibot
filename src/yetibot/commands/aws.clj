(ns yetibot.commands.aws
  (:require
    [taoensso.timbre :refer [info]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.api.aws :as aws]))

(defn iam-create-group-cmd
  "aws iam create-group <group-name> # Creates an aws IAM group named"
  {:yb/cat #{:util :info}}
  [{[_ group-name] :match}]
  "Implementation in progress")

(when (aws/configured?)
  (cmd-hook #"aws"
            #"iam create-group\s+(\S+)" iam-create-group-cmd))

