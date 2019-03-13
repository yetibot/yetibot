(ns yetibot.api.aws
  (:require
    [yetibot.core.schema :refer [non-empty-str]]
    [schema.core :as sch]
    [yetibot.core.config :refer [get-config]]
    [cognitect.aws.client.api :as aws]))

(def aws-schema
  {:aws-access-key-id         non-empty-str
   :aws-secret-access-key     non-empty-str
   (sch/optional-key :region) non-empty-str
   (sch/optional-key :dev-mode) sch/Bool})

(defn config
  "Returns AWS-related configuration"
  []
  (:value (get-config aws-schema [:aws])))

(defn configured? [] (config))

; aws API credentials
(def aws-access-key-id (:aws-access-key-id (config)))
(def aws-secret-access-key (:aws-secret-access-key (config)))
(def dev-mode? (:dev-mode (config)))

(defn make-aws-client
  "Returns an aws client given an aws keywordized service name (:iam, :ec2, :s3 etc.)"
  [service-name]
  (let [client (aws/client {:api                  service-name
                            :credentials-provider (cognitect.aws.credentials/basic-credentials-provider
                                                    {:access-key-id     aws-access-key-id
                                                     :secret-access-key aws-secret-access-key})})]
    (when (and (not (nil? dev-mode?))
               (true? dev-mode?))
      (aws/validate-requests client true))
    client))

; AWS clients
(def iam (when (configured?) (make-aws-client :iam)))

(defn iam-create-group
  "Creates an aws IAM group within the specified path"
  ([group-name]
   (iam-create-group "/" group-name))
  ([path group-name]
   (aws/invoke iam {:op      :CreateGroup
                    :request {:Path      path
                              :GroupName group-name}})))

(defn iam-create-user
  "Creates an aws IAM user"
  ([user-name]
   (iam-create-user "/" user-name))
  ([path user-name]
   (aws/invoke iam {:op      :CreateUser
                    :request {:Path     path
                              :UserName user-name}})))

(defn iam-add-user-to-group
  "Adds an IAM user to a group"
  [user-name group-name]
  (aws/invoke iam {:op      :AddUserToGroup
                   :request {:UserName  user-name
                             :GroupName group-name}}))

(defn iam-get-group
  "Returns the list of IAM user associated with this group"
  [groupe-name]
  (aws/invoke iam {:op      :GetGroup
                   :request {:GroupName groupe-name}}))

(defn iam-list-groups
  "Returns the list of IAM groups that have the specified path prefix"
  ([]
   (iam-list-groups "/"))
  ([path]
   (aws/invoke iam {:op      :ListGroups
                    :request {:PathPrefix path}})))

(defn iam-delete-user
  "Deletes the specified IAM user"
  [user-name]
  (aws/invoke iam {:op      :DeleteUser
                   :request {:UserName user-name}}))

(defn iam-get-user
  "Retrieves information about the specified IAM user"
  [user-name]
  (aws/invoke iam {:op      :GetUser
                   :request {:UserName user-name}}))

(defn iam-list-users
  "Returns the list of IAM users that have the specified path prefix"
  ([]
   (iam-list-users "/"))
  ([path]
   (aws/invoke iam {:op      :ListUsers
                    :request {:PathPrefix path}})))

(defn iam-delete-group
  "Deletes the specified IAM group"
  [group-name]
  (aws/invoke iam {:op      :DeleteGroup
                   :request {:GroupName group-name}}))

(defn iam-list-policies
  "Lists IAM policies available within the api account"
  ([]
   (iam-list-policies "All" "/"))
  ([path]
   (iam-list-policies "All" path))
  ([scope path]
   (aws/invoke iam {:op      :ListPolicies
                    :request {:Scope scope
                              :Path  path}})))

(defn iam-attach-user-policy
  "Attaches an IAM policy to a user"
  [user-name policy-arn]
  (aws/invoke iam {:op      :AttachUserPolicy
                   :request {:UserName  user-name
                             :PolicyArn policy-arn}}))

(defn iam-list-attached-user-policies
  "Lists all managed policies attached to a user"
  ([user-name]
   (iam-list-attached-user-policies "/" user-name))
  ([path user-name]
   (aws/invoke iam {:op      :ListAttachedUserPolicies
                    :request {:Path     path
                              :UserName user-name}})))

(defn iam-create-login-profile
  "Sets a temporary password for the specified user"
  [user-name password]
  (aws/invoke iam {:op      :CreateLoginProfile
                   :request {:UserName              user-name
                             :Password              password
                             :PasswordResetRequired true}}))

(defn iam-update-login-profile
  "Updates the specified user login profile"
  [user-name password]
  (aws/invoke iam {:op      :UpdateLoginProfile
                   :request {:UserName              user-name
                             :Password              password
                             :PasswordResetRequired true}}))

(defn iam-create-access-key
  "Creates an aws secret access key and corresponding aws access key ID"
  [user-name]
  (aws/invoke iam {:op      :CreateAccessKey
                   :request {:UserName user-name}}))

(defn iam-list-access-keys
  "Lists aws access key IDs associated with the specified user"
  [user-name]
  (aws/invoke iam {:op      :ListAccessKeys
                   :request {:UserName user-name}}))

(defn iam-delete-access-key
  "Deletes the specified user access key having the provided access key ID"
  [user-name access-key-id]
  (aws/invoke iam {:op      :DeleteAccessKey
                   :request {:UserName    user-name
                             :AccessKeyId access-key-id}}))