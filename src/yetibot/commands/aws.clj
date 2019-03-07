(ns yetibot.commands.aws
  (:require
    [taoensso.timbre :refer [info]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.api.aws :as aws]
    [clojure.spec.alpha :as s]))

; AWS API call responses generic fields specs
(s/def ::Path string?)
(s/def ::Arn string?)
(s/def ::CreateDate inst?)
(s/def ::ResponseMetadata (s/keys :req-un [::RequestId]))
(s/def ::xmlns string?)

; AWS User-related specs
(s/def ::UserName string?)
(s/def ::UserId string?)
(s/def ::User (s/keys :req-un [::Path ::UserName ::UserId ::Arn ::CreateDate]))
(s/def ::Users (s/* ::User))
(s/def ::DeleteUserResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::DeleteUserResponseAttrs (s/keys :req-un [::xmlns]))

; AWS Group-related specs
(s/def ::GroupName string?)
(s/def ::GroupId string?)
(s/def ::Group (s/keys :req-un [::Path ::GroupName ::GroupId ::Arn ::CreateDate]))
(s/def ::Groups (s/* ::Group))
(s/def ::DeleteGroupResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::DeleteGroupResponseAttrs (s/keys :req-un [::xmlns]))
(s/def ::GroupDeleted (s/keys :req-un [::DeleteGroupResponse
                                       ::DeleteGroupResponseAttrs]))

; AWS add-user-to-group-related specs
(s/def ::RequestId string?)

(s/def ::AddUserToGroupResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::AddUserToGroupResponseAttrs (s/keys :req-un [::xmlns]))

; AWS iam create-user response
(s/def ::CreatedUser (s/keys :req-un [::User]))
; AWS iam create-group response
(s/def ::CreatedGroup (s/keys :req-un [::Group]))
; AWS iam add-user-to-group response
(s/def ::UserAddedToGroup (s/keys :req-un [::AddUserToGroupResponse
                                           ::AddUserToGroupResponseAttrs]))
; AWS iam delete-user response
(s/def ::UserDeleted (s/keys :req-un [::DeleteUserResponse
                                      ::DeleteUserResponseAttrs]))
; AWS iam list-users response
(s/def ::ListUsersResponse (s/keys :req-un [::Users ::IsTruncated]
                                   :opt [::Marker]))

; AWS get-group related specs
(s/def ::IsTruncated boolean?)
(s/def ::Marker string?)
(s/def ::GetGroupResponse (s/keys :req-un [::Group ::Users ::IsTruncated]
                                  :opt [::Marker]))
(s/def ::ListGroupsResponse (s/keys :req-un [::Groups ::IsTruncated]
                                    :opt [::Marker]))

; AWS get-user related spec
(s/def ::GetUserResponse (s/keys :req-un [::User]))

; AWS list-policies related spec
(s/def ::Policy (s/keys :req-un [::PermissionsBoundaryUsageCount
                                 ::Path
                                 ::CreateDate
                                 ::PolicyName
                                 ::AttachmentCount
                                 ::DefaultVersionId
                                 ::IsAttachable
                                 ::Arn
                                 ::PolicyId
                                 ::UpdateDate]))

(s/def ::Policies (s/* ::Policy))
(s/def ::ListPoliciesResponse (s/keys :req-un [::Policies ::IsTruncated ::Marker]))

(s/def ::AttachUserPolicyResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::AttachUserPolicyResponseAttrs (s/keys :req-un [::xmlns]))
; Attach User Policy to user
(s/def ::IAMUserPolicyAttached (s/keys :req-un [::AttachUserPolicyResponse
                                                ::AttachUserPolicyResponseAttrs]))

(s/def ::AttachedPolicy (s/keys :req-un [::PolicyName ::PolicyArn]))
(s/def ::AttachedPolicies (s/* ::AttachedPolicy))
(s/def ::ListAttachedUserPoliciesResponse (s/keys :req-un [::AttachedPolicies]))

; user-profile related specs
(s/def ::PasswordResetRequired boolean?)
(s/def ::LoginProfile (s/keys :req-un [::UserName ::CreateDate ::PasswordResetRequired]))
(s/def ::LoginProfileCreated (s/keys :req-un [::LoginProfile]))

(s/def ::UpdateLoginProfileResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::UpdateLoginProfileResponseAttrs (s/keys :req-un [::xmlns]))
(s/def ::LoginProfileUpdated (s/keys :req-un [::UpdateLoginProfileResponse
                                              ::UpdateLoginProfileResponseAttrs]))

; access-key related specs
(s/def ::AccessKeyId string?)
(s/def ::Status string?)
(s/def ::SecretAccessKey string?)
(s/def ::AccessKey (s/keys :req-un [::UserName ::AccessKeyId ::Status ::SecretAccessKey ::CreateDate]))
(s/def ::CreatedAccessKey (s/keys :req-un [::AccessKey]))

(s/def ::AccessKeyInfo (s/keys :req-un [::UserName ::AccessKeyId ::Status ::CreateDate]))
(s/def ::AccessKeyMetadata (s/* ::AccessKeyInfo))
(s/def ::ListAccessKeysResponse (s/keys :req-un [::AccessKeyMetadata]))

(s/def ::DeleteAccessKeyResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::DeleteAccessKeyResponseAttrs (s/keys :req-un [::xmlns]))
(s/def ::AccessKeyDeleted (s/keys :req-un [::DeleteAccessKeyResponse
                                           ::DeleteAccessKeyResponseAttrs]))

; AWS API response formatting
(defmulti format-response
          "Returns a dispatch-value matching the operation that has been successfully invoked
          or has failed"
          (fn [response]
            (let [aws-type (:aws/type (meta response))]
              (cond
                (and (s/valid? ::CreatedGroup response)
                     (= aws-type :aws.type/CreatedGroup)) ::IAMGroupCreated
                (and (s/valid? ::CreatedUser response)
                     (= aws-type :aws.type/CreatedUser)) ::IAMUserCreated
                (and (s/valid? ::GetGroupResponse response)
                     (= aws-type :aws.type/GetGroupResponse)) ::IAMGetGroupResponseReceived
                (and (s/valid? ::UserAddedToGroup response)
                     (= aws-type :aws.type/UserAddedToGroup)) ::IAMUserAddedToGroup
                (and (s/valid? ::ListGroupsResponse response)
                     (= aws-type :aws.type/ListGroupsResponse)) ::IAMListGroupsResponseReceived
                (and (s/valid? ::UserDeleted response)
                     (= aws-type :aws.type/UserDeleted)) ::IAMUserDeleted
                (and (s/valid? ::GetUserResponse response)
                     (= aws-type :aws.type/GetUserResponse)) ::IAMGetUserResponseReceived
                (and (s/valid? ::ListUsersResponse response)
                     (= aws-type :aws.type/ListUsersResponse)) ::IAMListUsersResponseReceived
                (and (s/valid? ::GroupDeleted response)
                     (= aws-type :aws.type/GroupDeleted)) ::IAMGroupDeleted
                (and (s/valid? ::ListPoliciesResponse response)
                     (= aws-type :aws.type/ListPoliciesResponse)) ::IAMListPoliciesResponseReceived
                (and (s/valid? ::IAMUserPolicyAttached response)
                     (= aws-type :aws.type/UserPolicyAttached)) ::IAMUserPolicyAttached
                (and (s/valid? ::ListAttachedUserPoliciesResponse response)
                     (= aws-type :aws.type/ListAttachedUserPoliciesResponse)) ::IAMListAttachedUserPoliciesResponseReceived
                (and (s/valid? ::LoginProfileCreated response)
                     (= aws-type :aws.type/LoginProfileCreated)) ::IAMLoginProfileCreated
                (and (s/valid? ::LoginProfileUpdated response)
                     (= aws-type :aws.type/LoginProfileUpdated)) ::IAMLoginProfileUpdated
                (and (s/valid? ::CreatedAccessKey response)
                     (= aws-type :aws.type/CreatedAccessKey)) ::IAMAccessKeyCreated
                (and (s/valid? ::ListAccessKeysResponse response)
                     (= aws-type :aws.type/ListAccessKeysResponse)) ::IAMListAccessKeysResponseReceived
                (and (s/valid? ::AccessKeyDeleted response)
                     (= aws-type :aws.type/AccessKeyDeleted)) ::IAMAccessKeyDeleted
                :else ::error))))

(defmethod format-response ::error
  [response]
  {:result/error (get-in response [:ErrorResponse :Error :Message])})

(defmethod format-response ::IAMGroupCreated
  [{{:keys [Path GroupName GroupId Arn CreateDate]} :Group}]
  {:result/data  {:path Path, :group-name GroupName, :group-id GroupId, :arn Arn, :create-date CreateDate}
   :result/value (format "Group %s/%s [Id=%s, Arn=%s] has been created successfully on %s"
                         Path GroupName GroupId Arn CreateDate)})

(defmethod format-response ::IAMUserCreated
  [{{:keys [Path UserName UserId Arn CreateDate]} :User}]
  {:result/data  {:path Path, :user-name UserName, :user-id UserId, :arn Arn, :create-date CreateDate}
   :result/value (format "User %s/%s [Id=%s, Arn=%s] has been created successfully on %s"
                         Path UserName UserId Arn CreateDate)})

(defmethod format-response ::IAMUserAddedToGroup
  [response]
  (let [request-id (get-in response [:AddUserToGroupResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "User successfully added to group [RequestId=%s]"
                           request-id)}))

(defmethod format-response ::IAMUserDeleted
  [response]
  (let [request-id (get-in response [:DeleteUserResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "User successfully deleted [RequestId=%s]"
                           request-id)}))

(defmethod format-response ::IAMGroupDeleted
  [response]
  (let [request-id (get-in response [:DeleteGroupResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "Group successfully deleted [RequestId=%s]"
                           request-id)}))

(defmethod format-response ::IAMGetGroupResponseReceived
  [{:keys [Group Users]}]
  (let [{:keys [Path GroupName GroupId Arn CreateDate]} Group]
    {:result/data  {:path Path, :group-name GroupName, :group-id GroupId, :arn Arn, :create-date CreateDate, :users Users}
     :result/value (conj (map #(format "User : %s/%s [UserId=%s, Arn=%s] - Created on %s"
                                       (:Path %) (:UserName %) (:UserId %) (:Arn %) (:CreateDate %))
                              Users)
                         (format "Group : %s/%s\nGroupId : %s\nArn : %s\nCreateDate : %s\n\n"
                                 Path GroupName GroupId Arn CreateDate))}))

(defmethod format-response ::IAMListGroupsResponseReceived
  [{:keys [Groups]}]
  {:result/data  {:groups Groups}
   :result/value (map #(format "Group : %s/%s\nGroupId : %s\nArn : %s\nCreateDate : %s\n"
                               (:Path %) (:GroupName %) (:GroupId %) (:Arn %) (:CreateDate %))
                      Groups)})

(defmethod format-response ::IAMGetUserResponseReceived
  [{:keys [User]}]
  (let [{:keys [Path UserName UserId Arn CreateDate]} User]
    {:result/data  {:path Path, :user-name UserName, :user-id UserId, :arn Arn, :create-date CreateDate}
     :result/value (format "User : %s/%s [UserId=%s, Arn=%s] - Created on %s"
                           Path UserName UserId Arn CreateDate)}))

(defmethod format-response ::IAMListUsersResponseReceived
  [{:keys [Users]}]
  {:result/data  {:users Users}
   :result/value (map #(format "User : %s/%s [UserId=%s, Arn=%s] - Created on %s"
                               (:Path %) (:UserName %) (:UserId %) (:Arn %) (:CreateDate %))
                      Users)})

(defmethod format-response ::IAMListPoliciesResponseReceived
  [{:keys [Policies]}]
  {:result/data  {:policies Policies}
   :result/value (map #(format "Policy name : %s/%s [PolicyId=%s, Arn=%s] - Created on %s"
                               (:Path %) (:PolicyName %) (:PolicyId %) (:Arn %) (:CreateDate %))
                      Policies)})

(defmethod format-response ::IAMUserPolicyAttached
  [response]
  (let [request-id (get-in response [:AttachUserPolicyResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "User policy successfully attached [RequestId=%s]"
                           request-id)}))

(defmethod format-response ::IAMListAttachedUserPoliciesResponseReceived
  [{:keys [AttachedPolicies]}]
  {:result/data  {:attached-policies AttachedPolicies}
   :result/value (map #(format "Policy name : %s [Arn=%s]"
                               (:PolicyName %) (:PolicyArn %))
                      AttachedPolicies)})

(defmethod format-response ::IAMLoginProfileCreated
  [{:keys [LoginProfile]}]
  (let [user-name (:UserName LoginProfile)
        create-date (:CreateDate LoginProfile)]
    {:result/data  {:user-name user-name, :create-date create-date}
     :result/value (format "Login profile for %s, requiring password reset, successfully created on %s"
                           user-name create-date)}))

(defmethod format-response ::IAMLoginProfileUpdated
  [response]
  (let [request-id (get-in response [:UpdateLoginProfileResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "Login profile successfully updated [RequestId=%s]"
                           request-id)}))

(defmethod format-response ::IAMAccessKeyCreated
  [{{:keys [UserName AccessKeyId Status SecretAccessKey CreateDate]} :AccessKey}]
  {:result/data  {:user-name UserName, :access-key-id AccessKeyId, :status Status, :secret-access-key SecretAccessKey, :create-date CreateDate}
   :result/value (format "An access key for user %s has been successfully created on %s\nAWS Access Key ID : %s\nAWS Secret Access Key : %s\nStatus : %s\n"
                         UserName CreateDate AccessKeyId SecretAccessKey Status)})

(defmethod format-response ::IAMListAccessKeysResponseReceived
  [{:keys [AccessKeyMetadata]}]
  {:result/data  {:access-key-metadata AccessKeyMetadata}
   :result/value (map #(format "Access key ID : %s\nStatus : %s\nCreated on : %s\n"
                               (:AccessKeyId %) (:Status %) (:CreateDate %))
                      AccessKeyMetadata)})

(defmethod format-response ::IAMAccessKeyDeleted
  [response]
  (let [request-id (get-in response [:DeleteAccessKeyResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "Access Key successfully deleted [RequestId=%s]"
                           request-id)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn iam-create-group-in-path-cmd
  "aws iam create-group <path> <group-name> # Creates an aws IAM group named <group-name> within the specified <path>"
  {:yb/cat #{:util :info}}
  [{[_ path group-name] :match}]
  (-> (aws/iam-create-group path group-name)
      (with-meta {:aws/type :aws.type/CreatedGroup})
      format-response))

(defn iam-create-group-cmd
  "aws iam create-group <group-name> # Creates an aws IAM group named <group-name> within the default path /"
  {:yb/cat #{:util :info}}
  [{[_ group-name] :match}]
  (-> (aws/iam-create-group group-name)
      (with-meta {:aws/type :aws.type/CreatedGroup})
      format-response))

(defn iam-create-user-cmd
  "aws iam create-user <user-name> # Creates an aws IAM user named <user-name>"
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-create-user user-name)
      (with-meta {:aws/type :aws.type/CreatedUser})
      format-response))

(defn iam-create-user-in-path-cmd
  "aws iam create-user <path> <user-name> # Creates an aws IAM user named <user-name> within the specified <path> prefix"
  {:yb/cat #{:util :info}}
  [{[_ path user-name] :match}]
  (-> (aws/iam-create-user path user-name)
      (with-meta {:aws/type :aws.type/CreatedUser})
      format-response))

(defn iam-add-user-to-group-cmd
  "aws iam add-user-to-group <user-name> <group-name> # Adds an aws IAM user named <user-name> to an IAM group named <group-name>"
  {:yb/cat #{:util :info}}
  [{[_ user-name group-name] :match}]
  (-> (aws/iam-add-user-to-group user-name group-name)
      (with-meta {:aws/type :aws.type/UserAddedToGroup})
      format-response))

(defn iam-get-group-cmd
  "aws iam get-group <group-name> # Gets IAM user info associated with the <group-name> group"
  {:yb/cat #{:util :info}}
  [{[_ group-name] :match}]
  (-> (aws/iam-get-group group-name)
      (with-meta {:aws/type :aws.type/GetGroupResponse})
      format-response))

(defn iam-list-groups-in-path-cmd
  "aws iam list-groups <path-prefix> # Lists the IAM groups that have the specified path prefix <path-prefix>"
  {:yb/cat #{:util :info}}
  [{[_ path] :match}]
  (-> (aws/iam-list-groups path)
      (with-meta {:aws/type :aws.type/ListGroupsResponse})
      format-response))

(defn iam-list-groups-cmd
  "aws iam list-groups # Lists the IAM groups in the default / path"
  {:yb/cat #{:util :info}}
  [_]
  (-> (aws/iam-list-groups)
      (with-meta {:aws/type :aws.type/ListGroupsResponse})
      format-response))

(defn iam-delete-user-cmd
  "aws iam delete-user <user-name> # Deletes the specified IAM user. The user must not belong to any groups or have any access keys, signing certificates, or attached policies."
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-delete-user user-name)
      (with-meta {:aws/type :aws.type/UserDeleted})
      format-response))

(defn iam-get-user-cmd
  "aws iam get-user <user-name> # Retrieves information about the specified IAM user"
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-get-user user-name)
      (with-meta {:aws/type :aws.type/GetUserResponse})
      format-response))

(defn iam-list-users-cmd
  "aws iam list-users # Lists the IAM users within the default / prefix"
  {:yb/cat #{:util :info}}
  [_]
  (-> (aws/iam-list-users)
      (with-meta {:aws/type :aws.type/ListUsersResponse})
      format-response))

(defn iam-list-users-in-path-cmd
  "aws iam list-users <path> # Lists the IAM users that have the specified path prefix"
  {:yb/cat #{:util :info}}
  [{[_ path] :match}]
  (-> (aws/iam-list-users path)
      (with-meta {:aws/type :aws.type/ListUsersResponse})
      format-response))

(defn iam-delete-group-cmd
  "aws iam delete-group <group-name> # Deletes the specified IAM group. The group must not contain any users or have any attached policies."
  {:yb/cat #{:util :info}}
  [{[_ group-name] :match}]
  (-> (aws/iam-delete-group group-name)
      (with-meta {:aws/type :aws.type/GroupDeleted})
      format-response))

(defn iam-list-policies-cmd
  "aws iam list-policies # Lists all the managed policies that are available in your AWS account"
  {:yb/cat #{:util :info}}
  [_]
  (-> (aws/iam-list-policies)
      (with-meta {:aws/type :aws.type/ListPoliciesResponse})
      format-response))

(defn iam-list-policies-in-path-cmd
  "aws iam list-policies <path> # Lists all the managed policies that are available in your AWS account within the specified <path>"
  {:yb/cat #{:util :info}}
  [{[_ path] :match}]
  (-> (aws/iam-list-policies path)
      (with-meta {:aws/type :aws.type/ListPoliciesResponse})
      format-response))

(defn iam-list-policies-with-scope-in-path-cmd
  "aws iam list-policies <scope> <path> # Lists all the managed policies that are available in your AWS account within the specified <path> and <scope>"
  {:yb/cat #{:util :info}}
  [{[_ scope path] :match}]
  (-> (aws/iam-list-policies scope path)
      (with-meta {:aws/type :aws.type/ListPoliciesResponse})
      format-response))

(defn iam-attach-user-policy-cmd
  "aws iam attach-user-policy <user-name> <arn> # Attaches the specified managed policy whose Arn is <arn> to the specified user."
  {:yb/cat #{:util :info}}
  [{[_ user-name policy-arn] :match}]
  (-> (aws/iam-attach-user-policy user-name policy-arn)
      (with-meta {:aws/type :aws.type/UserPolicyAttached})
      format-response))

(defn iam-list-attached-user-policies-cmd
  "aws iam list-attached-user-policies <user-name> # Lists all managed policies that are attached to the specified IAM user."
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-list-attached-user-policies user-name)
      (with-meta {:aws/type :aws.type/ListAttachedUserPoliciesResponse})
      format-response))

(defn iam-list-attached-user-policies-in-path-cmd
  "aws iam list-attached-user-policies <path> <user-name> # Lists all managed policies that are attached to the specified IAM user having the specified <path>."
  {:yb/cat #{:util :info}}
  [{[_ path user-name] :match}]
  (-> (aws/iam-list-attached-user-policies path user-name)
      (with-meta {:aws/type :aws.type/ListAttachedUserPoliciesResponse})
      format-response))

(defn iam-create-login-profile-cmd
  "aws iam create-login-profile <user-name> <password> # Creates a temporary password for the specified user, giving the user the
  ability to access AWS services through the AWS Management Console and change it the first time they connect."
  {:yb/cat #{:util :info}}
  [{[_ user-name password] :match}]
  (-> (aws/iam-create-login-profile user-name password)
      (with-meta {:aws/type :aws.type/LoginProfileCreated})
      format-response))

(defn iam-update-login-profile-cmd
  "aws iam update-login-profile <user-name> <password> # Updates the login profile for the specified user. The password has to
  be updated by the user at first login."
  {:yb/cat #{:util :info}}
  [{[_ user-name password] :match}]
  (-> (aws/iam-update-login-profile user-name password)
      (with-meta {:aws/type :aws.type/LoginProfileUpdated})
      format-response))

(defn iam-create-access-key-cmd
  "aws iam create-access-key <user-name> # Creates a new AWS secret access key and corresponding AWS access key ID for the specified user"
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-create-access-key user-name)
      (with-meta {:aws/type :aws.type/CreatedAccessKey})
      format-response))

(defn iam-list-access-keys-cmd
  "aws iam list-access-keys <user-name> # Returns information about the access key IDs associated with the specified IAM user"
  {:yb/cat #{:util :info}}
  [{[_ user-name] :match}]
  (-> (aws/iam-list-access-keys user-name)
      (with-meta {:aws/type :aws.type/ListAccessKeysResponse})
      format-response))

(defn iam-delete-access-key-cmd
  "aws iam delete-access-key <user-name> <access-key-id> # Deletes the access key pair associated with the specified IAM user"
  {:yb/cat #{:util :info}}
  [{[_ user-name access-key-id] :match}]
  (-> (aws/iam-delete-access-key user-name access-key-id)
      (with-meta {:aws/type :aws.type/AccessKeyDeleted})
      format-response))

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
            #"iam list-attached-user-policies\s+(\S+)" iam-list-attached-user-policies-cmd
            #"iam create-login-profile\s+(\S+)\s+(\S+)" iam-create-login-profile-cmd
            #"iam update-login-profile\s+(\S+)\s+(\S+)" iam-update-login-profile-cmd
            #"iam create-access-key\s+(\S+)" iam-create-access-key-cmd
            #"iam list-access-keys\s+(\S+)" iam-list-access-keys-cmd
            #"iam delete-access-key\s+(\S+)\s+(\S+)" iam-delete-access-key-cmd))

