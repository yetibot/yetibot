(ns yetibot.commands.aws
  (:require
    [taoensso.timbre :refer [info]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.api.aws :as aws]))

(defn iam-create-group-in-path-cmd
  "aws iam create-group <path> <group-name> # Creates an aws IAM group named <group-name> within the specified <path>"
  {:yb/cat #{:util :info}}
  [{[_ path group-name] :match}]
  (aws/iam-create-group path group-name))

(defn iam-create-group-cmd
  "aws iam create-group <group-name> # Creates an aws IAM group named <group-name> within the default path /"
  {:yb/cat #{:util :info}}
  [{[_ group-name] :match}]
  (aws/iam-create-group group-name))

(defn iam-create-user-cmd
  "aws iam create-user <user-name> # Creates an aws IAM user named <user-name>"
  [{[_ user-name] :match}]
  (aws/iam-create-user user-name))

(defn iam-create-user-in-path-cmd
  "aws iam create-user <path> <user-name> # Creates an aws IAM user named <user-name> within the specified <path> prefix"
  [{[_ path user-name] :match}]
  (aws/iam-create-user path user-name))

(defn iam-add-user-to-group-cmd
  "aws iam add-user-to-group <user-name> <group-name> # Adds an aws IAM user named <user-name> to an IAM group named <group-name>"
  [{[_ user-name group-name] :match}]
  (aws/iam-add-user-to-group user-name group-name))

(defn iam-get-group-cmd
  "aws iam get-group <group-name> # Gets IAM user info associated with the <group-name> group"
  [{[_ group-name] :match}]
  (aws/iam-get-group group-name))

(defn iam-list-groups-in-path-cmd
  "aws iam list-groups <path-prefix> # Lists the IAM groups that have the specified path prefix <path-prefix>"
  [{[_ path] :match}]
  (aws/iam-list-groups path))

(defn iam-list-groups-cmd
  "aws iam list-groups # Lists the IAM groups in the default / path"
  [_]
  (aws/iam-list-groups))

(defn iam-delete-user-cmd
  "aws iam delete-user <user-name> # Deletes the specified IAM user. The user must not belong to any groups or have any access keys, signing certificates, or attached policies."
  [{[_ user-name] :match}]
  (aws/iam-delete-user user-name))

(defn iam-get-user-cmd
  "aws iam get-user <user-name> # Retrieves information about the specified IAM user"
  [{[_ user-name] :match}]
  (aws/iam-get-user user-name))

(defn iam-list-users-cmd
  "aws iam list-users # Lists the IAM users within the default / prefix"
  [_]
  (aws/iam-list-users "/"))

(defn iam-list-users-in-path-cmd
  "aws iam list-users <path> # Lists the IAM users that have the specified path prefix"
  [{[_ path] :match}]
  (aws/iam-list-users path))

(defn iam-delete-group-cmd
  "aws iam delete-group <group-name> # Deletes the specified IAM group. The group must not contain any users or have any attached policies."
  [{[_ group-name] :match}]
  (aws/iam-delete-group group-name))

(defn iam-list-policies-cmd
  "aws iam list-policies # Lists all the managed policies that are available in your AWS account"
  [_]
  (aws/iam-list-policies))

(defn iam-list-policies-in-path-cmd
  "aws iam list-policies <path> # Lists all the managed policies that are available in your AWS account within the specified <path>"
  [{[_ path] :match}]
  (aws/iam-list-policies path))

(defn iam-list-policies-with-scope-in-path-cmd
  "aws iam list-policies <scope> <path> # Lists all the managed policies that are available in your AWS account within the specified <path> and <scope>"
  [{[_ scope path] :match}]
  (aws/iam-list-policies scope path))

(defn iam-attach-user-policy-cmd
  "aws iam attach-user-policy <user-name> <arn> # Attaches the specified managed policy whose Arn is <arn> to the specified user."
  [{[_ user-name policy-arn] :match}]
  (aws/iam-attach-user-policy user-name policy-arn))

(defn iam-list-attached-user-policies-cmd
  "aws iam list-attached-user-policies <user-name> # Lists all managed policies that are attached to the specified IAM user."
  [{[_ user-name] :match}]
  (aws/iam-list-attached-user-policies user-name))

(defn iam-list-attached-user-policies-in-path-cmd
  "aws iam list-attached-user-policies <path> <user-name> # Lists all managed policies that are attached to the specified IAM user having the specified <path>."
  [{[_ path user-name] :match}]
  (aws/iam-list-attached-user-policies path user-name))

(when (aws/configured?)
  (cmd-hook #"aws"
            #"iam create-group\s+(\S+)\s+(\S+)" iam-create-group-in-path-cmd
            #"iam create-group\s+(\S+)" iam-create-group-cmd
            #"iam list-groups\s+(\S+)" iam-list-groups-in-path-cmd
            #"iam list-groups" iam-list-groups-cmd
            #"iam get-group\s+(\S+)" iam-get-group-cmd
            #"iam delete-group\s+(\S+)" iam-delete-group-cmd
            #"iam create-user\s+(\S+)\s+(\S+)" iam-create-user-in-path-cmd
            #"iam create-user\s+(\S+)" iam-create-user-cmd
            #"iam list-users\s+(\S+)" iam-list-users-in-path-cmd
            #"iam list-users" iam-list-users-cmd
            #"iam get-user\s+(\S+)" iam-get-user-cmd
            #"iam delete-user\s+(\S+)" iam-delete-user-cmd
            #"iam add-user-to-group\s+(\S+)\s+(\S+)" iam-add-user-to-group-cmd
            #"iam list-policies\s+(\S+)\s+(\S+)" iam-list-policies-with-scope-in-path-cmd
            #"iam list-policies\s+(\S+)" iam-list-policies-in-path-cmd
            #"iam list-policies" iam-list-policies-cmd
            #"iam attach-user-policy\s+(\S+)\s+(\S+)" iam-attach-user-policy-cmd
            #"iam list-attached-user-policies\s+(\S+)\s+(\S+)" iam-list-attached-user-policies-in-path-cmd
            #"iam list-attached-user-policies\s+(\S+)" iam-list-attached-user-policies-cmd))
