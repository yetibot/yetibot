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
(s/def ::Users (s/* ::User))

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
; AWS get-group related specs
(s/def ::IsTruncated boolean?)
(s/def ::Marker string?)
(s/def ::GetGroupResponse (s/keys :req-un [::Group ::Users ::IsTruncated]
                                  :opt [::Marker]))

; Mutli-method for aws api call response formatting
(defmulti format-response
          "Returns a dispatch-value matching the operation that has been successfully invoked
          or has failed"
          (fn [response]
            (cond
              ; order matters when stacking up the specs in the `cond` here. Broad scope specs must come first here.
              ; In fact, ::IAMGroupCreated is a `subset` of ::IAMGetGroupResponseReceived : both contain the `::Group`-related
              ; spec for one of their attribute and hence it is sufficient for a data whose shape contains a :Group to be matched
              ; by both.
              ; As a general rule of thumb, the more specific the spec (the more attributes, and hence specs, there is to be
              ; matched/conformed), the higher it should be put in the `cond` stack...
              (s/valid? ::GetGroupResponse response) ::IAMGetGroupResponseReceived
              (s/valid? ::UserAddedToGroup response) ::IAMUserAddedToGroup
              (s/valid? ::CreatedUser response) ::IAMUserCreated
              (s/valid? ::CreatedGroup response) ::IAMGroupCreated
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

(defmethod format-response ::IAMGetGroupResponseReceived
  [{:keys [Group Users]}]
  (let [{:keys [Path GroupName GroupId Arn CreateDate]} Group]
    (conj (map #(format "User : %s/%s [UserId=%s, Arn=%s] - Created on %s"
                        (:Path %) (:UserName %) (:UserId %) (:Arn %) (:CreateDate %))
               Users)
          (format "Group : %s/%s\nGroupId : %s\nArn : %s\nCreateDate : %s\n\n"
                  Path GroupName GroupId Arn CreateDate))))

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

(defn iam-get-group
  "Returns the list of IAM user associated with this group"
  [groupe-name]
  (let [response (aws/invoke iam {:op :GetGroup
                                  :request {:GroupName groupe-name}})]
    (format-response response)))