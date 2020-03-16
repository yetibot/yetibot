(ns yetibot.commands.aws
  (:require
    [taoensso.timbre :refer [info]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.api.aws :as aws]
    [yetibot.commands.aws.formatters :refer [format-iam-response format-s3-response]]))

(defn iam-create-group-in-path-cmd
  "aws iam create-group <path> <group-name> # Creates an aws IAM group named <group-name> within the specified <path>"
  {:yb/cat #{:util :info}}
  [{[_ path group-name] :match}]
  (-> (aws/iam-create-group path group-name)
      (with-meta {:aws/type :aws.type/CreatedGroup})
      format-iam-response))

(defn iam-create-group-cmd
  "aws iam create-group <group-name> # Creates an aws IAM group named <group-name> within the default path /"
  {:yb/cat #{:util :info}}
  [{[_ group-name] :match}]
  (-> (aws/iam-create-group "/" group-name)
      (with-meta {:aws/type :aws.type/CreatedGroup})
      format-iam-response))

(defn iam-create-user-cmd
  "aws iam create-user <user-name> # Creates an aws IAM user named <user-name>"
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
    (-> (aws/iam-create-user "/" user-name)
        (with-meta {:aws/type :aws.type/CreatedUser})
        format-iam-response))

(defn iam-create-user-in-path-cmd
  "aws iam create-user <path> <user-name> # Creates an aws IAM user named <user-name> within the specified <path> prefix"
  {:yb/cat #{:util :info}}
  [{[_ path user-name] :match}]
  (-> (aws/iam-create-user path user-name)
      (with-meta {:aws/type :aws.type/CreatedUser})
      format-iam-response))

(defn iam-add-user-to-group-cmd
  "aws iam add-user-to-group <group-name> <user-name> # Adds an aws IAM user <user-name> to an IAM group <group-name>"
  {:yb/cat #{:util :info}}
  [{[_ group-name user-name] :match}]
  (-> (aws/iam-add-user-to-group group-name user-name)
      (with-meta {:aws/type :aws.type/UserAddedToGroup})
      format-iam-response))

(defn iam-remove-user-from-group-cmd
  "aws iam remove-user-from-group <group-name> <user-name> # Removes an aws IAM user <user-name> from the IAM group <group-name>"
  {:yb/cat #{:util :info}}
  [{[_ group-name user-name] :match}]
  (-> (aws/iam-remove-user-from-group group-name user-name)
      (with-meta {:aws/type :aws.type/UserRemovedFromGroup})
      format-iam-response))

(defn iam-get-group-cmd
  "aws iam get-group <group-name> # Gets IAM user info associated with the <group-name> group"
  {:yb/cat #{:util :info}}
  [{[_ group-name] :match}]
  (-> (aws/iam-get-group group-name)
      (with-meta {:aws/type :aws.type/GetGroupResponse})
      format-iam-response))

(defn iam-list-groups-in-path-cmd
  "aws iam list-groups <path-prefix> # Lists the IAM groups that have the specified path prefix <path-prefix>"
  {:yb/cat #{:util :info}}
  [{[_ path] :match}]
  (-> (aws/iam-list-groups path)
      (with-meta {:aws/type :aws.type/ListGroupsResponse})
      format-iam-response))

(defn iam-list-groups-cmd
  "aws iam list-groups # Lists the IAM groups in the default / path"
  {:yb/cat #{:util :info}}
  [_]
  (-> (aws/iam-list-groups "/")
      (with-meta {:aws/type :aws.type/ListGroupsResponse})
      format-iam-response))

(defn iam-delete-user-cmd
  "aws iam delete-user <user-name> # Deletes the specified IAM user. The user must not belong to any groups or have any access keys, signing certificates, or attached policies."
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-delete-user user-name)
      (with-meta {:aws/type :aws.type/UserDeleted})
      format-iam-response))

(defn iam-get-user-cmd
  "aws iam get-user <user-name> # Retrieves information about the specified IAM user"
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-get-user user-name)
      (with-meta {:aws/type :aws.type/GetUserResponse})
      format-iam-response))

(defn iam-list-users-cmd
  "aws iam list-users # Lists the IAM users within the default / prefix"
  {:yb/cat #{:util :info}}
  [_]
  (-> (aws/iam-list-users "/")
      (with-meta {:aws/type :aws.type/ListUsersResponse})
      format-iam-response))

(defn iam-list-users-in-path-cmd
  "aws iam list-users <path> # Lists the IAM users that have the specified path prefix"
  {:yb/cat #{:util :info}}
  [{[_ path] :match}]
  (-> (aws/iam-list-users path)
      (with-meta {:aws/type :aws.type/ListUsersResponse})
      format-iam-response))

(defn iam-delete-group-cmd
  "aws iam delete-group <group-name> # Deletes the specified IAM group. The group must not contain any users or have any attached policies."
  {:yb/cat #{:util :info}}
  [{[_ group-name] :match}]
  (-> (aws/iam-delete-group group-name)
      (with-meta {:aws/type :aws.type/GroupDeleted})
      format-iam-response))

(defn iam-list-policies-cmd
  "aws iam list-policies # Lists all the managed policies that are available in your AWS account"
  {:yb/cat #{:util :info}}
  [_]
  (-> (aws/iam-list-policies "All" "/")
      (with-meta {:aws/type :aws.type/ListPoliciesResponse})
      format-iam-response))

(defn iam-list-policies-in-path-cmd
  "aws iam list-policies <path> # Lists all the managed policies that are available in your AWS account within the specified <path>"
  {:yb/cat #{:util :info}}
  [{[_ path] :match}]
  (-> (aws/iam-list-policies "All" path)
      (with-meta {:aws/type :aws.type/ListPoliciesResponse})
      format-iam-response))

(defn iam-list-policies-with-scope-in-path-cmd
  "aws iam list-policies <scope> <path> # Lists all the managed policies that are available in your AWS account within the specified <path> and <scope>"
  {:yb/cat #{:util :info}}
  [{[_ scope path] :match}]
  (-> (aws/iam-list-policies scope path)
      (with-meta {:aws/type :aws.type/ListPoliciesResponse})
      format-iam-response))

(defn iam-attach-user-policy-cmd
  "aws iam attach-user-policy <user-name> <arn> # Attaches the specified managed policy whose Arn is <arn> to the specified user."
  {:yb/cat #{:util :info}}
  [{[_ user-name policy-arn] :match}]
  (-> (aws/iam-attach-user-policy user-name policy-arn)
      (with-meta {:aws/type :aws.type/UserPolicyAttached})
      format-iam-response))

(defn iam-list-attached-user-policies-cmd
  "aws iam list-attached-user-policies <user-name> # Lists all managed policies that are attached to the specified IAM user."
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-list-attached-user-policies "/" user-name)
      (with-meta {:aws/type :aws.type/ListAttachedUserPoliciesResponse})
      format-iam-response))

(defn iam-list-attached-user-policies-in-path-cmd
  "aws iam list-attached-user-policies <path> <user-name> # Lists all managed policies that are attached to the specified IAM user having the specified <path>."
  {:yb/cat #{:util :info}}
  [{[_ path user-name] :match}]
  (-> (aws/iam-list-attached-user-policies path user-name)
      (with-meta {:aws/type :aws.type/ListAttachedUserPoliciesResponse})
      format-iam-response))

(defn iam-create-login-profile-cmd
  "aws iam create-login-profile <user-name> <password> # Creates a temporary password for the specified user, giving the user the
  ability to access AWS services through the AWS Management Console and change it the first time they connect."
  {:yb/cat #{:util :info}}
  [{[_ user-name password] :match}]
  (-> (aws/iam-create-login-profile user-name password true)
      (with-meta {:aws/type :aws.type/LoginProfileCreated})
      format-iam-response))

(defn iam-update-login-profile-cmd
  "aws iam update-login-profile <user-name> <password> # Updates the login profile for the specified user. The password has to
  be updated by the user at first login."
  {:yb/cat #{:util :info}}
  [{[_ user-name password] :match}]
  (-> (aws/iam-update-login-profile user-name password true)
      (with-meta {:aws/type :aws.type/LoginProfileUpdated})
      format-iam-response))

(defn iam-create-access-key-cmd
  "aws iam create-access-key <user-name> # Creates a new AWS secret access key and corresponding AWS access key ID for the specified user"
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-create-access-key user-name)
      (with-meta {:aws/type :aws.type/CreatedAccessKey})
      format-iam-response))

(defn iam-list-access-keys-cmd
  "aws iam list-access-keys <user-name> # Returns information about the access key IDs associated with the specified IAM user"
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-list-access-keys user-name)
      (with-meta {:aws/type :aws.type/ListAccessKeysResponse})
      format-iam-response))

(defn iam-delete-access-key-cmd
  "aws iam delete-access-key <user-name> <access-key-id> # Deletes the access key pair associated with the specified IAM user"
  {:yb/cat #{:util :info}}
  [{[_ user-name access-key-id] :match}]
  (-> (aws/iam-delete-access-key user-name access-key-id)
      (with-meta {:aws/type :aws.type/AccessKeyDeleted})
      format-iam-response))

(defn s3-create-bucket-cmd
  "aws s3 mb s3://<bucket-name> # Creates a new s3 bucket"
  {:yb/cat #{:util :info}}
  [{[_ bucket-name] :match}]
  (-> (aws/s3-create-bucket bucket-name {:LocationConstraint aws/region})
      (with-meta {:aws/type :aws.type/CreateBucket})
      format-s3-response))

(defn s3-list-buckets-cmd
  "aws s3 ls # Lists all buckets owned by the associated aws credentials"
  {:yb/cat #{:util :info}}
  [{[_] :match}]
  (-> (aws/s3-list-buckets [])
      (with-meta {:aws/type :aws.type/ListBuckets})
      format-s3-response))

(defn s3-list-objects-cmd
  "aws s3 ls <bucket-name> # Lists objects in s3 bucket <bucket-name>"
  {:yb/cat #{:util :info}}
  [{[_ bucket-name] :match}]
  (-> (aws/s3-list-objects bucket-name)
      (with-meta {:aws/type :aws.type/ListObjects})
      format-s3-response))

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
            #"iam remove-user-from-group\s+(\S+)\s+(\S+)" iam-remove-user-from-group-cmd
            #"iam list-policies\s+(\S+)\s+(\S+)" iam-list-policies-with-scope-in-path-cmd
            #"iam list-policies\s+(\S+)" iam-list-policies-in-path-cmd
            #"iam list-policies" iam-list-policies-cmd
            #"iam attach-user-policy\s+(\S+)\s+(\S+)" iam-attach-user-policy-cmd
            #"iam list-attached-user-policies\s+(\S+)\s+(\S+)" iam-list-attached-user-policies-in-path-cmd
            #"iam list-attached-user-policies\s+(\S+)" iam-list-attached-user-policies-cmd
            #"iam create-login-profile\s+(\S+)\s+(\S+)" iam-create-login-profile-cmd
            #"iam update-login-profile\s+(\S+)\s+(\S+)" iam-update-login-profile-cmd
            #"iam create-access-key\s+(\S+)" iam-create-access-key-cmd
            #"iam list-access-keys\s+(\S+)" iam-list-access-keys-cmd
            #"iam delete-access-key\s+(\S+)\s+(\S+)" iam-delete-access-key-cmd
            #"s3 mb s3://(\S+)" s3-create-bucket-cmd
            #"s3 ls\s+(\S+)" s3-list-objects-cmd
            #"s3 ls" s3-list-buckets-cmd

