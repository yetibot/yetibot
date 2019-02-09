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

(defn iam-get-group-cmd
  "aws iam get-group <group-name> # Gets IAM user info associated with the <group-name> group"
  [{[_ group-name] :match}]
  (aws/iam-get-group group-name))

(defn iam-list-groups-cmd
  "aws iam list-groups <path-prefix> # Lists the IAM groups that have the specified path prefix <path-prefix>"
  [{[_ path] :match}]
  (aws/iam-list-groups path))

(defn iam-delete-user-cmd
  "aws iam delete-user <user-name> # Deletes the specified IAM user. The user must not belong to any groups or have any access keys, signing certificates, or attached policies."
  [{[_ user-name] :match}]
  (aws/iam-delete-user user-name))

(defn iam-get-user-cmd
  "aws iam get-user <user-name> # Retrieves information about the specified IAM user"
  [{[_ user-name] :match}]
  (aws/iam-get-user user-name))

(when (aws/configured?)
  (cmd-hook #"aws"
            #"iam create-group\s+(\S+)" iam-create-group-cmd
            #"iam list-groups\s+(\S+)" iam-list-groups-cmd
            #"iam create-user\s+(\S+)" iam-create-user-cmd
            #"iam get-user\s+(\S+)" iam-get-user-cmd
            #"iam delete-user\s+(\S+)" iam-delete-user-cmd
            #"iam add-user-to-group\s+(\S+)\s+(\S+)" iam-add-user-to-group-cmd
            #"iam get-group\s+(\S+)" iam-get-group-cmd))

