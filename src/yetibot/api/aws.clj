(ns yetibot.api.aws
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.spec :as yspec]
    [cognitect.aws.client.api :as aws]))

(s/def ::aws-access-key-id ::yspec/non-blank-string)

(s/def ::aws-secret-access-key ::yspec/non-blank-string)

(s/def ::region ::yspec/non-blank-string)

(s/def ::dev-mode boolean?)

(s/def ::config (s/keys :req-un [::aws-access-key-id
                                 ::aws-secret-access-key]
                        :opt-un [::region ::dev-mode]))

(defn config
  "Returns AWS-related configuration"
  []
  (:value (get-config ::config [:aws])))

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
  (when (configured?)
    (let [client (aws/client {:api                  service-name
                              :credentials-provider (cognitect.aws.credentials/basic-credentials-provider
                                                      {:access-key-id     aws-access-key-id
                                                       :secret-access-key aws-secret-access-key})
                              :region               region})]
      (when dev-mode?
        (aws/validate-requests client true))
      client)))

; AWS clients
(def iam (make-aws-client :iam))

(defn- aws-invoke
  [aws-client op arg-keys & args]
  (aws/invoke aws-client {:op op :request (zipmap arg-keys (vec args))}))

(def iam-create-group
  "Creates an aws IAM group within the specified path"
  (partial aws-invoke iam :CreateGroup [:Path :GroupName]))

(def iam-create-user
  "Creates an aws IAM user"
  (partial aws-invoke iam :CreateUser [:Path :UserName]))

(def iam-add-user-to-group
  "Adds an IAM user to a group"
  (partial aws-invoke iam :AddUserToGroup [:UserName :GroupName]))

(def iam-remove-user-from-group
  "Removes an IAM user from a group"
  (partial aws-invoke iam :RemoveUserFromGroup [:UserName :GroupName]))

(def iam-get-group
  "Returns the list of IAM user associated with this group"
  (partial aws-invoke iam :GetGroup [:GroupName]))

(def iam-list-groups
  "Returns the list of IAM groups that have the specified path prefix"
  (partial aws-invoke iam :ListGroups [:PathPrefix]))

(def iam-delete-user
  "Deletes the specified IAM user"
  (partial aws-invoke iam :DeleteUser [:UserName]))

(def iam-get-user
  "Retrieves information about the specified IAM user"
  (partial aws-invoke iam :GetUser [:UserName]))

(def iam-list-users
  "Returns the list of IAM users that have the specified path prefix"
  (partial aws-invoke iam :ListUsers [:PathPrefix]))

(def iam-delete-group
  "Deletes the specified IAM group"
  (partial aws-invoke iam :DeleteGroup [:GroupName]))

(def iam-list-policies
  "Lists IAM policies available within the api account"
  (partial aws-invoke iam :ListPolicies [:Scope :Path]))

(def iam-attach-user-policy
  "Attaches an IAM policy to a user"
  (partial aws-invoke iam :AttachUserPolicy [:UserName :PolicyArn]))

(def iam-list-attached-user-policies
  "Lists all managed policies attached to a user"
  (partial aws-invoke iam :ListAttachedUserPolicies [:Path :UserName]))

(def iam-create-login-profile
  "Sets a temporary password for the specified user"
  (partial aws-invoke iam :CreateLoginProfile [:UserName :Password :PasswordResetRequired]))

(def iam-update-login-profile
  "Updates the specified user login profile"
  (partial aws-invoke iam :UpdateLoginProfile [:UserName :Password :PasswordResetRequired]))

(def iam-create-access-key
  "Creates an aws secret access key and corresponding aws access key ID"
  (partial aws-invoke iam :CreateAccessKey [:UserName]))

(def iam-list-access-keys
  "Lists aws access key IDs associated with the specified user"
  (partial aws-invoke iam :ListAccessKeys [:UserName]))

(def iam-delete-access-key
  "Deletes the specified user access key having the provided access key ID"
  (partial aws-invoke iam :DeleteAccessKey [:UserName :AccessKeyId]))