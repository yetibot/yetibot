(ns yetibot.commands.aws.specs
  (:require
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