(ns yetibot.commands.aws.formatters
  (:require
    [clojure.spec.alpha :as s]
    [yetibot.commands.aws.specs :as aws.spec]))

; AWS API response formatting utility function
(defmulti format-response
          "Returns a dispatch-value matching the operation that has been successfully invoked
          or has failed"
          (fn [response]
            (let [aws-type (:aws/type (meta response))]
              (cond
                (and (s/valid? ::aws.spec/CreatedGroup response)
                     (= aws-type :aws.type/CreatedGroup)) ::IAMGroupCreated
                (and (s/valid? ::aws.spec/CreatedUser response)
                     (= aws-type :aws.type/CreatedUser)) ::IAMUserCreated
                (and (s/valid? ::aws.spec/GetGroupResponse response)
                     (= aws-type :aws.type/GetGroupResponse)) ::IAMGetGroupResponseReceived
                (and (s/valid? ::aws.spec/UserAddedToGroup response)
                     (= aws-type :aws.type/UserAddedToGroup)) ::IAMUserAddedToGroup
                (and (s/valid? ::aws.spec/ListGroupsResponse response)
                     (= aws-type :aws.type/ListGroupsResponse)) ::IAMListGroupsResponseReceived
                (and (s/valid? ::aws.spec/UserDeleted response)
                     (= aws-type :aws.type/UserDeleted)) ::IAMUserDeleted
                (and (s/valid? ::aws.spec/GetUserResponse response)
                     (= aws-type :aws.type/GetUserResponse)) ::IAMGetUserResponseReceived
                (and (s/valid? ::aws.spec/ListUsersResponse response)
                     (= aws-type :aws.type/ListUsersResponse)) ::IAMListUsersResponseReceived
                (and (s/valid? ::aws.spec/GroupDeleted response)
                     (= aws-type :aws.type/GroupDeleted)) ::IAMGroupDeleted
                (and (s/valid? ::aws.spec/ListPoliciesResponse response)
                     (= aws-type :aws.type/ListPoliciesResponse)) ::IAMListPoliciesResponseReceived
                (and (s/valid? ::aws.spec/IAMUserPolicyAttached response)
                     (= aws-type :aws.type/UserPolicyAttached)) ::IAMUserPolicyAttached
                (and (s/valid? ::aws.spec/ListAttachedUserPoliciesResponse response)
                     (= aws-type :aws.type/ListAttachedUserPoliciesResponse)) ::IAMListAttachedUserPoliciesResponseReceived
                (and (s/valid? ::aws.spec/LoginProfileCreated response)
                     (= aws-type :aws.type/LoginProfileCreated)) ::IAMLoginProfileCreated
                (and (s/valid? ::aws.spec/LoginProfileUpdated response)
                     (= aws-type :aws.type/LoginProfileUpdated)) ::IAMLoginProfileUpdated
                (and (s/valid? ::aws.spec/CreatedAccessKey response)
                     (= aws-type :aws.type/CreatedAccessKey)) ::IAMAccessKeyCreated
                (and (s/valid? ::aws.spec/ListAccessKeysResponse response)
                     (= aws-type :aws.type/ListAccessKeysResponse)) ::IAMListAccessKeysResponseReceived
                (and (s/valid? ::aws.spec/AccessKeyDeleted response)
                     (= aws-type :aws.type/AccessKeyDeleted)) ::IAMAccessKeyDeleted
                :else ::error))))

(defmethod format-response ::error
  [response]
  {:result/error (get-in response [:ErrorResponse :Error :Message])})

(defmethod format-response ::IAMGroupCreated
  [{{:keys [Path GroupName GroupId Arn CreateDate]} :Group}]
  {:result/data  {:path Path, :group-name GroupName, :group-id GroupId, :arn Arn, :create-date CreateDate}
   :result/value (format "Group %s%s [Id=%s, Arn=%s] has been created successfully on %s"
                         Path GroupName GroupId Arn CreateDate)})

(defmethod format-response ::IAMUserCreated
  [{{:keys [Path UserName UserId Arn CreateDate]} :User}]
  {:result/data  {:path Path, :user-name UserName, :user-id UserId, :arn Arn, :create-date CreateDate}
   :result/value (format "User %s%s [Id=%s, Arn=%s] has been created successfully on %s"
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
     :result/value (conj (map #(format "User : %s%s [UserId=%s, Arn=%s] - Created on %s"
                                       (:Path %) (:UserName %) (:UserId %) (:Arn %) (:CreateDate %))
                              Users)
                         (format "Group : %s%s\nGroupId : %s\nArn : %s\nCreateDate : %s\n\n"
                                 Path GroupName GroupId Arn CreateDate))}))

(defmethod format-response ::IAMListGroupsResponseReceived
  [{:keys [Groups]}]
  {:result/data  {:groups Groups}
   :result/value (map #(format "Group : %s%s\nGroupId : %s\nArn : %s\nCreateDate : %s\n"
                               (:Path %) (:GroupName %) (:GroupId %) (:Arn %) (:CreateDate %))
                      Groups)})

(defmethod format-response ::IAMGetUserResponseReceived
  [{:keys [User]}]
  (let [{:keys [Path UserName UserId Arn CreateDate]} User]
    {:result/data  {:path Path, :user-name UserName, :user-id UserId, :arn Arn, :create-date CreateDate}
     :result/value (format "User : %s%s [UserId=%s, Arn=%s] - Created on %s"
                           Path UserName UserId Arn CreateDate)}))

(defmethod format-response ::IAMListUsersResponseReceived
  [{:keys [Users]}]
  {:result/data  {:users Users}
   :result/value (map #(format "User : %s%s [UserId=%s, Arn=%s] - Created on %s"
                               (:Path %) (:UserName %) (:UserId %) (:Arn %) (:CreateDate %))
                      Users)})

(defmethod format-response ::IAMListPoliciesResponseReceived
  [{:keys [Policies]}]
  {:result/data  {:policies Policies}
   :result/value (map #(format "Policy name : %s%s [PolicyId=%s, Arn=%s] - Created on %s"
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