(ns yetibot.commands.aws
  (:require
    [taoensso.timbre :refer [info]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.api.aws :as aws]))

(defn iam-create-group-cmd
  "aws iam create-group <group-name> # Creates an aws IAM group named <group-name>"
  {:yb/cat #{:util :info}}
  [{[_ group-name] :match}]
  (aws/iam-create-group group-name))

(defn iam-create-user-cmd
  "aws iam create-user <user-name> # Creates an aws IAM user named <user-name>"
  [{[_ user-name] :match}]
  (aws/iam-create-user user-name))

(defn iam-add-user-to-group-cmd
  "aws iam add-user-to-group <user-name> <group-name> # Adds an aws IAM user named <user-name> to an IAM group named <group-name>"
  [{[_ user-name group-name] :match}]
  (aws/iam-add-user-to-group user-name group-name))

(when (aws/configured?)
  (cmd-hook #"aws"
            #"iam create-group\s+(\S+)" iam-create-group-cmd
            #"iam create-user\s+(\S+)" iam-create-user-cmd
            #"iam add-user-to-group\s+(\S+)\s+(\S+)" iam-add-user-to-group-cmd))

