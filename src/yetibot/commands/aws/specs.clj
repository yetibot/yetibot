(ns yetibot.commands.aws.specs
  (:require
    [clojure.spec.alpha :as s]))

(s/def ::RequestId string?)
(s/def ::xmlns string?)
(s/def ::ResponseMetadata (s/keys :req-un [::RequestId]))

(s/def ::DeleteGroupResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::DeleteGroupResponseAttrs (s/keys :req-un [::xmlns]))
(s/def ::GroupDeleted (s/keys :req-un [::DeleteGroupResponse
                                       ::DeleteGroupResponseAttrs]))

(s/def ::DeleteUserResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::DeleteUserResponseAttrs (s/keys :req-un [::xmlns]))
(s/def ::UserDeleted (s/keys :req-un [::DeleteUserResponse
                                      ::DeleteUserResponseAttrs]))

(s/def ::AddUserToGroupResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::AddUserToGroupResponseAttrs (s/keys :req-un [::xmlns]))
(s/def ::UserAddedToGroup (s/keys :req-un [::AddUserToGroupResponse
                                           ::AddUserToGroupResponseAttrs]))

(s/def ::RemoveUserFromGroupResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::RemoveUserFromGroupResponseAttrs (s/keys :req-un [::xmlns]))
(s/def ::UserRemovedFromGroup (s/keys :req-un [::RemoveUserFromGroupResponse
                                               ::RemoveUserFromGroupResponseAttrs]))

(s/def ::AttachUserPolicyResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::AttachUserPolicyResponseAttrs (s/keys :req-un [::xmlns]))
(s/def ::IAMUserPolicyAttached (s/keys :req-un [::AttachUserPolicyResponse
                                                ::AttachUserPolicyResponseAttrs]))

(s/def ::UpdateLoginProfileResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::UpdateLoginProfileResponseAttrs (s/keys :req-un [::xmlns]))
(s/def ::LoginProfileUpdated (s/keys :req-un [::UpdateLoginProfileResponse
                                              ::UpdateLoginProfileResponseAttrs]))

(s/def ::DeleteAccessKeyResponse (s/keys :req-un [::ResponseMetadata]))
(s/def ::DeleteAccessKeyResponseAttrs (s/keys :req-un [::xmlns]))
(s/def ::AccessKeyDeleted (s/keys :req-un [::DeleteAccessKeyResponse
                                           ::DeleteAccessKeyResponseAttrs]))
