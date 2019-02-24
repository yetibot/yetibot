(ns yetibot.api.aws
  (:require
    [yetibot.core.schema :refer [non-empty-str]]
    [schema.core :as sch]
    [yetibot.core.config :refer [get-config]]
    [cognitect.aws.client.api :as aws]
    [clojure.spec.alpha :as s]))

(def aws-schema
  {:aws-access-key-id         non-empty-str
   :aws-secret-access-key     non-empty-str
   (sch/optional-key :region) non-empty-str})

(defn config
  "Returns AWS-related configuration"
  []
  (:value (get-config aws-schema [:aws])))

(defn configured? [] (config))

; aws API credentials
(def aws-access-key-id (:aws-access-key-id (config)))
(def aws-secret-access-key (:aws-secret-access-key (config)))

(defn make-aws-client
  "Returns an aws client given an aws keywordized service name (:iam, :ec2, :s3 etc.)"
  [service-name]
  (aws/client {:api                  service-name
               :credentials-provider (cognitect.aws.credentials/basic-credentials-provider
                                       {:access-key-id     aws-access-key-id
                                        :secret-access-key aws-secret-access-key})}))
; AWS clients
(def iam (make-aws-client :iam))

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
; aws iam list-users response
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

; Mutli-method for aws api call response formatting
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
                :else ::error))))

(defmethod format-response ::error
  [response]
  {:result/error (get-in response [:ErrorResponse :Error :Message])})

(defmethod format-response ::IAMGroupCreated
  [{{:keys [Path GroupName GroupId Arn CreateDate]} :Group}]
  (format "Group %s/%s [Id=%s, Arn=%s] has been created successfully on %s"
          Path GroupName GroupId Arn CreateDate))

(defmethod format-response ::IAMUserCreated
  [{{:keys [Path UserName UserId Arn CreateDate]} :User}]
  (format "User %s/%s [Id=%s, Arn=%s] has been created successfully on %s"
          Path UserName UserId Arn CreateDate))

(defmethod format-response ::IAMUserAddedToGroup
  [response]
  (format "User successfully added to group [RequestId=%s]"
          (get-in response [:AddUserToGroupResponse :ResponseMetadata :RequestId])))

(defmethod format-response ::IAMUserDeleted
  [response]
  (format "User successfully deleted [RequestId=%s]"
          (get-in response [:DeleteUserResponse :ResponseMetadata :RequestId])))

(defmethod format-response ::IAMGroupDeleted
  [response]
  (format "Group successfully deleted [RequestId=%s]"
          (get-in response [:DeleteGroupResponse :ResponseMetadata :RequestId])))

(defmethod format-response ::IAMGetGroupResponseReceived
  [{:keys [Group Users]}]
  (let [{:keys [Path GroupName GroupId Arn CreateDate]} Group]
    (conj (map #(format "User : %s/%s [UserId=%s, Arn=%s] - Created on %s"
                        (:Path %) (:UserName %) (:UserId %) (:Arn %) (:CreateDate %))
               Users)
          (format "Group : %s/%s\nGroupId : %s\nArn : %s\nCreateDate : %s\n\n"
                  Path GroupName GroupId Arn CreateDate))))

(defmethod format-response ::IAMListGroupsResponseReceived
  [{:keys [Groups]}]
  (map #(format "Group : %s/%s\nGroupId : %s\nArn : %s\nCreateDate : %s\n"
                (:Path %) (:GroupName %) (:GroupId %) (:Arn %) (:CreateDate %))
       Groups))

(defmethod format-response ::IAMGetUserResponseReceived
  [{:keys [User]}]
  (let [{:keys [Path UserName UserId Arn CreateDate]} User]
    (format "User : %s/%s [UserId=%s, Arn=%s] - Created on %s"
            Path UserName UserId Arn CreateDate)))

(defmethod format-response ::IAMListUsersResponseReceived
  [{:keys [Users]}]
  (map #(format "User : %s/%s [UserId=%s, Arn=%s] - Created on %s"
                (:Path %) (:UserName %) (:UserId %) (:Arn %) (:CreateDate %))
       Users))

(defmethod format-response ::IAMListPoliciesResponseReceived
  [{:keys [Policies]}]
  (map #(format "Policy name : %s/%s [PolicyId=%s, Arn=%s] - Created on %s"
                (:Path %) (:PolicyName %) (:PolicyId %) (:Arn %) (:CreateDate %))
       Policies))

(defmethod format-response ::IAMUserPolicyAttached
  [response]
  (format "User policy successfully attached [RequestId=%s]"
          (get-in response [:AttachUserPolicyResponse :ResponseMetadata :RequestId])))

(defmethod format-response ::IAMListAttachedUserPoliciesResponseReceived
  [{:keys [AttachedPolicies]}]
  (map #(format "Policy name : %s [Arn=%s]"
                (:PolicyName %) (:PolicyArn %))
       AttachedPolicies))

(defn iam-create-group
  "Creates an aws IAM group within the specified path"
  ([group-name]
   (iam-create-group "/" group-name))
  ([path group-name]
   (let [response (aws/invoke iam {:op      :CreateGroup
                                   :request {:Path      path
                                             :GroupName group-name}})]
     (-> response
         (with-meta {:aws/type :aws.type/CreatedGroup})
         format-response))))

(defn iam-create-user
  "Creates an aws IAM user"
  ([user-name]
   (iam-create-user "/" user-name))
  ([path user-name]
   (let [response (aws/invoke iam {:op      :CreateUser
                                   :request {:Path     path
                                             :UserName user-name}})]
     (-> response
         (with-meta {:aws/type :aws.type/CreatedUser})
         format-response))))

(defn iam-add-user-to-group
  "Adds an IAM user to a group"
  [user-name group-name]
  (let [response (aws/invoke iam {:op      :AddUserToGroup
                                  :request {:UserName  user-name
                                            :GroupName group-name}})]
    (-> response
        (with-meta {:aws/type :aws.type/UserAddedToGroup})
        format-response)))

(defn iam-get-group
  "Returns the list of IAM user associated with this group"
  [groupe-name]
  (let [response (aws/invoke iam {:op      :GetGroup
                                  :request {:GroupName groupe-name}})]
    (-> response
        (with-meta {:aws/type :aws.type/GetGroupResponse})
        format-response)))

(defn iam-list-groups
  "Returns the list of IAM groups that have the specified path prefix"
  ([]
   (iam-list-groups "/"))
  ([path]
   (let [response (aws/invoke iam {:op      :ListGroups
                                   :request {:PathPrefix path}})]
     (-> response
         (with-meta {:aws/type :aws.type/ListGroupsResponse})
         format-response))))

(defn iam-delete-user
  "Deletes the specified IAM user"
  [user-name]
  (let [response (aws/invoke iam {:op      :DeleteUser
                                  :request {:UserName user-name}})]
    (-> response
        (with-meta {:aws/type :aws.type/UserDeleted})
        format-response)))

(defn iam-get-user
  "Retrieves information about the specified IAM user"
  [user-name]
  (let [response (aws/invoke iam {:op      :GetUser
                                  :request {:UserName user-name}})]
    (-> response
        (with-meta {:aws/type :aws.type/GetUserResponse})
        format-response)))

(defn iam-list-users
  "Returns the list of IAM users that have the specified path prefix"
  [path]
  (let [response (aws/invoke iam {:op      :ListUsers
                                  :request {:PathPrefix path}})]
    (-> response
        (with-meta {:aws/type :aws.type/ListUsersResponse})
        format-response)))

(defn iam-delete-group
  "Deletes the specified IAM group"
  [group-name]
  (let [response (aws/invoke iam {:op      :DeleteGroup
                                  :request {:GroupName group-name}})]
    (-> response
        (with-meta {:aws/type :aws.type/GroupDeleted})
        format-response)))

(defn iam-list-policies
  "Lists IAM policies available within the api account"
  ([]
   (iam-list-policies "All" "/"))
  ([path]
   (iam-list-policies "All" path))
  ([scope path]
   (let [response (aws/invoke iam {:op      :ListPolicies
                                   :request {:Scope scope
                                             :Path  path}})]
     (-> response
         (with-meta {:aws/type :aws.type/ListPoliciesResponse})
         format-response))))

(defn iam-attach-user-policy
  "Attaches an IAM policy to a user"
  [user-name policy-arn]
  (let [response (aws/invoke iam {:op      :AttachUserPolicy
                                  :request {:UserName  user-name
                                            :PolicyArn policy-arn}})]
    (-> response
        (with-meta {:aws/type :aws.type/UserPolicyAttached})
        format-response)))

(defn iam-list-attached-user-policies
  "Lists all managed policies attached to a user"
  ([user-name]
    (iam-list-attached-user-policies "/" user-name))
  ([path user-name]
   (let [response (aws/invoke iam {:op      :ListAttachedUserPolicies
                                   :request {:Path path
                                             :UserName user-name}})]
     (-> response
         (with-meta {:aws/type :aws.type/ListAttachedUserPoliciesResponse})
         format-response))))