(ns yetibot.api.aws
  (:require
    [yetibot.core.schema :refer [non-empty-str]]
    [schema.core :as sch]
    [yetibot.core.config :refer [get-config]]
    [cognitect.aws.client.api :as aws]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]))

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

; AWS User-related specs
(s/def ::UserName string?)
(s/def ::UserId string?)
(s/def ::User (s/keys :req-un [::Path ::UserName ::UserId ::Arn ::CreateDate]))

; AWS Group-related specs
(s/def ::GroupName string?)
(s/def ::GroupId string?)
(s/def ::Group (s/keys :req-un [::Path ::GroupName ::GroupId ::Arn ::CreateDate]))

; AWS add-user-to-group-related specs
(s/def ::RequestId string?)
(s/def ::xmlns string?)
(s/def ::ResponseMetadata (s/keys :req-un [::RequestId]))
(s/def ::AddUserToGroupResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::AddUserToGroupResponseAttrs (s/keys :req-un [::xmlns]))

; AWS iam create-user response
(s/def ::CreatedUser (s/keys :req-un [::User]))
; AWS iam create-group response
(s/def ::CreatedGroup (s/keys :req-un [::Group]))
; AWS iam add-user-to-group response
(s/def ::UserAddedToGroup (s/keys :req-un [::AddUserToGroupResponse
                                           ::AddUserToGroupResponseAttrs]))

; Mutli-method for aws api call response formatting
(defmulti format-response
          "Returns a dispatch-value matching the operation that has been successfully invoked
          or has failed"
          (fn [response]
            (cond
              (s/valid? ::CreatedUser response) ::IAMUserCreated
              (s/valid? ::CreatedGroup response) ::IAMGroupCreated
              (s/valid? ::UserAddedToGroup response) ::IAMUserAddedToGroup
              :else ::error)))

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

(defn iam-create-group
  "Creates an aws IAM group"
  [group-name]
  (let [response (aws/invoke iam {:op      :CreateGroup
                                  :request {:GroupName group-name}})]
    (format-response response)))

(defn iam-create-user
  "Creates an aws IAM user"
  [user-name]
  (let [response (aws/invoke iam {:op      :CreateUser
                                  :request {:UserName user-name}})]
    (format-response response)))

(defn iam-add-user-to-group
  "Adds an IAM user to a group"
  [user-name group-name]
  (let [response (aws/invoke iam {:op      :AddUserToGroup
                                  :request {:UserName  user-name
                                            :GroupName group-name}})]
    (format-response response)))