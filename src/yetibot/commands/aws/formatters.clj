(ns yetibot.commands.aws.formatters
  (:require
    [clojure.spec.alpha :as s]
    [cognitect.aws.client.api :as aws]
    [yetibot.commands.aws.specs :as aws.spec]
    [yetibot.api.aws]
    [taoensso.timbre :as log]
    [clojure.contrib.humanize :refer [filesize] :as h]))

(def iam-response-spec (partial aws/response-spec-key yetibot.api.aws/iam))
(def s3-response-spec (partial aws/response-spec-key yetibot.api.aws/s3))

; IAM AWS API response formatting utility function
(defmulti format-iam-response
          "Returns a dispatch-value matching the IAM operation that has been successfully invoked
          or has failed"
          (fn [response]
            (let [aws-type (:aws/type (meta response))]
              (cond
                (and (s/valid? (iam-response-spec :CreateGroup) response)
                     (= aws-type :aws.type/CreatedGroup)) ::IAMGroupCreated
                (and (s/valid? (iam-response-spec :CreateUser) response)
                     (= aws-type :aws.type/CreatedUser)) ::IAMUserCreated
                (and (s/valid? (iam-response-spec :GetGroup) response)
                     (= aws-type :aws.type/GetGroupResponse)) ::IAMGetGroupResponseReceived
                (and (s/valid? ::aws.spec/UserAddedToGroup response)
                     (= aws-type :aws.type/UserAddedToGroup)) ::IAMUserAddedToGroup
                (and (s/valid? ::aws.spec/UserRemovedFromGroup response)
                     (= aws-type :aws.type/UserRemovedFromGroup)) ::IAMUserRemovedFromGroup
                (and (s/valid? (iam-response-spec :ListGroups) response)
                     (= aws-type :aws.type/ListGroupsResponse)) ::IAMListGroupsResponseReceived
                (and (s/valid? ::aws.spec/UserDeleted response)
                     (= aws-type :aws.type/UserDeleted)) ::IAMUserDeleted
                (and (s/valid? (iam-response-spec :GetUser) response)
                     (= aws-type :aws.type/GetUserResponse)) ::IAMGetUserResponseReceived
                (and (s/valid? (iam-response-spec :ListUsers) response)
                     (= aws-type :aws.type/ListUsersResponse)) ::IAMListUsersResponseReceived
                (and (s/valid? ::aws.spec/GroupDeleted response)
                     (= aws-type :aws.type/GroupDeleted)) ::IAMGroupDeleted
                (and (s/valid? (iam-response-spec :ListPolicies) response)
                     (= aws-type :aws.type/ListPoliciesResponse)) ::IAMListPoliciesResponseReceived
                (and (s/valid? ::aws.spec/IAMUserPolicyAttached response)
                     (= aws-type :aws.type/UserPolicyAttached)) ::IAMUserPolicyAttached
                (and (s/valid? (iam-response-spec :ListAttachedUserPolicies) response)
                     (= aws-type :aws.type/ListAttachedUserPoliciesResponse)) ::IAMListAttachedUserPoliciesResponseReceived
                (and (s/valid? (iam-response-spec :CreateLoginProfile) response)
                     (= aws-type :aws.type/LoginProfileCreated)) ::IAMLoginProfileCreated
                (and (s/valid? ::aws.spec/LoginProfileUpdated response)
                     (= aws-type :aws.type/LoginProfileUpdated)) ::IAMLoginProfileUpdated
                (and (s/valid? (iam-response-spec :CreateAccessKey) response)
                     (= aws-type :aws.type/CreatedAccessKey)) ::IAMAccessKeyCreated
                (and (s/valid? (iam-response-spec :ListAccessKeys) response)
                     (= aws-type :aws.type/ListAccessKeysResponse)) ::IAMListAccessKeysResponseReceived
                (and (s/valid? ::aws.spec/AccessKeyDeleted response)
                     (= aws-type :aws.type/AccessKeyDeleted)) ::IAMAccessKeyDeleted
                :else ::error))))

(defmethod format-iam-response ::error
  [response]
  {:result/error (get-in response [:ErrorResponse :Error :Message])})

(defmethod format-iam-response ::IAMGroupCreated
  [{{:keys [Path GroupName GroupId Arn CreateDate]} :Group}]
  {:result/data  {:path Path, :group-name GroupName, :group-id GroupId, :arn Arn, :create-date CreateDate}
   :result/value (format "Group %s%s [Id=%s, Arn=%s] has been created successfully on %s"
                         Path GroupName GroupId Arn CreateDate)})

(defmethod format-iam-response ::IAMUserCreated
  [{{:keys [Path UserName UserId Arn CreateDate]} :User}]
  {:result/data  {:path Path, :user-name UserName, :user-id UserId, :arn Arn, :create-date CreateDate}
   :result/value (format "User %s%s [Id=%s, Arn=%s] has been created successfully on %s"
                         Path UserName UserId Arn CreateDate)})

(defmethod format-iam-response ::IAMUserAddedToGroup
  [response]
  (let [request-id (get-in response [:AddUserToGroupResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "User successfully added to group [RequestId=%s]"
                           request-id)}))

(defmethod format-iam-response ::IAMUserRemovedFromGroup
  [response]
  (let [request-id (get-in response [:RemoveUserFromGroupResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "User successfully removed from group [RequestId=%s]"
                           request-id)}))

(defmethod format-iam-response ::IAMUserDeleted
  [response]
  (let [request-id (get-in response [:DeleteUserResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "User successfully deleted [RequestId=%s]"
                           request-id)}))

(defmethod format-iam-response ::IAMGroupDeleted
  [response]
  (let [request-id (get-in response [:DeleteGroupResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "Group successfully deleted [RequestId=%s]"
                           request-id)}))

(defmethod format-iam-response ::IAMGetGroupResponseReceived
  [{:keys [Group Users]}]
  (let [{:keys [Path GroupName GroupId Arn CreateDate]} Group]
    {:result/data  {:path Path, :group-name GroupName, :group-id GroupId, :arn Arn, :create-date CreateDate, :users Users}
     :result/value (conj (map #(format "User : %s%s [UserId=%s, Arn=%s] - Created on %s"
                                       (:Path %) (:UserName %) (:UserId %) (:Arn %) (:CreateDate %))
                              Users)
                         (format "Group : %s%s\nGroupId : %s\nArn : %s\nCreateDate : %s\n\n"
                                 Path GroupName GroupId Arn CreateDate))}))

(defmethod format-iam-response ::IAMListGroupsResponseReceived
  [{:keys [Groups]}]
  {:result/data  {:groups Groups}
   :result/value (map #(format "Group : %s%s\nGroupId : %s\nArn : %s\nCreateDate : %s\n"
                               (:Path %) (:GroupName %) (:GroupId %) (:Arn %) (:CreateDate %))
                      Groups)})

(defmethod format-iam-response ::IAMGetUserResponseReceived
  [{:keys [User]}]
  (let [{:keys [Path UserName UserId Arn CreateDate]} User]
    {:result/data  {:path Path, :user-name UserName, :user-id UserId, :arn Arn, :create-date CreateDate}
     :result/value (format "User : %s%s [UserId=%s, Arn=%s] - Created on %s"
                           Path UserName UserId Arn CreateDate)}))

(defmethod format-iam-response ::IAMListUsersResponseReceived
  [{:keys [Users]}]
  {:result/data  {:users Users}
   :result/value (map #(format "User : %s%s [UserId=%s, Arn=%s] - Created on %s"
                               (:Path %) (:UserName %) (:UserId %) (:Arn %) (:CreateDate %))
                      Users)})

(defmethod format-iam-response ::IAMListPoliciesResponseReceived
  [{:keys [Policies]}]
  {:result/data  {:policies Policies}
   :result/value (map #(format "Policy name : %s%s [PolicyId=%s, Arn=%s] - Created on %s"
                               (:Path %) (:PolicyName %) (:PolicyId %) (:Arn %) (:CreateDate %))
                      Policies)})

(defmethod format-iam-response ::IAMUserPolicyAttached
  [response]
  (let [request-id (get-in response [:AttachUserPolicyResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "User policy successfully attached [RequestId=%s]"
                           request-id)}))

(defmethod format-iam-response ::IAMListAttachedUserPoliciesResponseReceived
  [{:keys [AttachedPolicies]}]
  {:result/data  {:attached-policies AttachedPolicies}
   :result/value (map #(format "Policy name : %s [Arn=%s]"
                               (:PolicyName %) (:PolicyArn %))
                      AttachedPolicies)})

(defmethod format-iam-response ::IAMLoginProfileCreated
  [{:keys [LoginProfile]}]
  (let [user-name (:UserName LoginProfile)
        create-date (:CreateDate LoginProfile)]
    {:result/data  {:user-name user-name, :create-date create-date}
     :result/value (format "Login profile for %s, requiring password reset, successfully created on %s"
                           user-name create-date)}))

(defmethod format-iam-response ::IAMLoginProfileUpdated
  [response]
  (let [request-id (get-in response [:UpdateLoginProfileResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "Login profile successfully updated [RequestId=%s]"
                           request-id)}))

(defmethod format-iam-response ::IAMAccessKeyCreated
  [{{:keys [UserName AccessKeyId Status SecretAccessKey CreateDate]} :AccessKey}]
  {:result/data  {:user-name UserName, :access-key-id AccessKeyId, :status Status, :secret-access-key SecretAccessKey, :create-date CreateDate}
   :result/value (format "An access key for user %s has been successfully created on %s\nAWS Access Key ID : %s\nAWS Secret Access Key : %s\nStatus : %s\n"
                         UserName CreateDate AccessKeyId SecretAccessKey Status)})

(defmethod format-iam-response ::IAMListAccessKeysResponseReceived
  [{:keys [AccessKeyMetadata]}]
  {:result/data  {:access-key-metadata AccessKeyMetadata}
   :result/value (map #(format "Access key ID : %s\nStatus : %s\nCreated on : %s\n"
                               (:AccessKeyId %) (:Status %) (:CreateDate %))
                      AccessKeyMetadata)})

(defmethod format-iam-response ::IAMAccessKeyDeleted
  [response]
  (let [request-id (get-in response [:DeleteAccessKeyResponse :ResponseMetadata :RequestId])]
    {:result/data  {:request-id request-id}
     :result/value (format "Access Key successfully deleted [RequestId=%s]"
                           request-id)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti format-s3-response
          "Returns a dispatch-value matching the S3 operation that has been successfully invoked
          or has failed"
          (fn [response]
            (let [aws-type (:aws/type (meta response))]
              (cond
                (and (s/valid? (s3-response-spec :CreateBucket) response)
                     (= aws-type :aws.type/CreateBucket)
                     (not (contains? response :Error))) ::S3BucketCreated
                (and (s/valid? (s3-response-spec :ListBuckets) response)
                     (= aws-type :aws.type/ListBuckets)
                     (not (contains? response :Error))) ::S3BucketListed
                (and (s/valid? (s3-response-spec :ListObjectsV2) response)
                     (= aws-type :aws.type/ListObjects)
                     (not (contains? response :Error))) ::S3ObjectsListed
                :else ::error))))

(defmethod format-s3-response ::error
  [response]
  {:result/error (get-in response [:Error :Message])})

(defmethod format-s3-response ::S3BucketCreated
  [{:keys [Location]}]
  {:result/data  {:location Location}
   :result/value (format "S3 bucket successfully created at %s" Location)})

(defmethod format-s3-response ::S3BucketListed
  [{:keys [Buckets Owner]}]
  {:result/data  {:buckets Buckets :owner Owner}
   :result/value (map
                   #(format "Bucket : %s - Created on %s" (:Name %) (:CreationDate %))
                   Buckets)})

(defmethod format-s3-response ::S3ObjectsListed
  [{:keys [Prefix StartAfter EncodingType Delimiter NextContinuationToken CommonPrefixes ContinuationToken Contents MaxKeys IsTruncated Name KeyCount]}]
  {:result/data  {:prefix                  Prefix
                  :start-after             StartAfter
                  :encoding-type           EncodingType
                  :delimiter               Delimiter
                  :next-continuation-token NextContinuationToken
                  :common-prefixes         CommonPrefixes
                  :continuation-token      ContinuationToken
                  :contents                Contents
                  :max-keys                MaxKeys
                  :is-truncated            IsTruncated
                  :name                    Name
                  :key-count               KeyCount}
   :result/value (map
                   #(format "%s[%s](%s) - Last modified on %s"
                            (:Key %) (:StorageClass %) (h/filesize (:Size %) :binary false) (:LastModified %))
                   Contents)})