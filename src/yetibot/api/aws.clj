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
(def region (:region (config)))
(def dev-mode? (and (not (nil? (:dev-mode (config))))
                    (true? (:dev-mode (config)))))

(defn make-aws-client
  "Returns an aws client given an aws keywordized service name (:iam, :ec2, :s3 etc.)"
  [service-name]
  (let [client (aws/client {:api                  service-name
                            :credentials-provider (cognitect.aws.credentials/basic-credentials-provider
                                                    {:access-key-id     aws-access-key-id
                                                     :secret-access-key aws-secret-access-key})
                            :region               region})]
    (when dev-mode?
      (aws/validate-requests client true))
    client))

; AWS clients
(def iam (when (configured?) (make-aws-client :iam)))

(defn- aws-invoke
  [op arg-keys & args]
  (aws/invoke iam {:op op :request (zipmap arg-keys (vec args))}))

(def iam-create-group
  "Creates an aws IAM group within the specified path"
  (partial aws-invoke :CreateGroup [:Path :GroupName]))

(def iam-create-user
  "Creates an aws IAM user"
  (partial aws-invoke :CreateUser [:Path :UserName]))

(def iam-add-user-to-group
  "Adds an IAM user to a group"
  (partial aws-invoke :AddUserToGroup [:UserName :GroupName]))

(def iam-remove-user-from-group
  "Removes an IAM user from a group"
  (partial aws-invoke :RemoveUserFromGroup [:UserName :GroupName]))

(def iam-get-group
  "Returns the list of IAM user associated with this group"
  (partial aws-invoke :GetGroup [:GroupName]))

(def iam-list-groups
  "Returns the list of IAM groups that have the specified path prefix"
  (partial aws-invoke :ListGroups [:PathPrefix]))

(def iam-delete-user
  "Deletes the specified IAM user"
  (partial aws-invoke :DeleteUser [:UserName]))

(def iam-get-user
  "Retrieves information about the specified IAM user"
  (partial aws-invoke :GetUser [:UserName]))

(def iam-list-users
  "Returns the list of IAM users that have the specified path prefix"
  (partial aws-invoke :ListUsers [:PathPrefix]))

(def iam-delete-group
  "Deletes the specified IAM group"
  (partial aws-invoke :DeleteGroup [:GroupName]))

(def iam-list-policies
  "Lists IAM policies available within the api account"
  (partial aws-invoke :ListPolicies [:Scope :Path]))

(def iam-attach-user-policy
  "Attaches an IAM policy to a user"
  (partial aws-invoke :AttachUserPolicy [:UserName :PolicyArn]))

(def iam-list-attached-user-policies
  "Lists all managed policies attached to a user"
  (partial aws-invoke :ListAttachedUserPolicies [:Path :UserName]))

(def iam-create-login-profile
  "Sets a temporary password for the specified user"
  (partial aws-invoke :CreateLoginProfile [:UserName :Password :PasswordResetRequired]))

(def iam-update-login-profile
  "Updates the specified user login profile"
  (partial aws-invoke :UpdateLoginProfile [:UserName :Password :PasswordResetRequired]))

(def iam-create-access-key
  "Creates an aws secret access key and corresponding aws access key ID"
  (partial aws-invoke :CreateAccessKey [:UserName]))

(def iam-list-access-keys
  "Lists aws access key IDs associated with the specified user"
  (partial aws-invoke :ListAccessKeys [:UserName]))

(def iam-delete-access-key
  "Deletes the specified user access key having the provided access key ID"
  (partial aws-invoke :DeleteAccessKey [:UserName :AccessKeyId]))