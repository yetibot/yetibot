(ns yetibot.api.aws
  (:require
    [yetibot.core.schema :refer [non-empty-str]]
    [schema.core :as sch]
    [yetibot.core.config :refer [get-config]]
    [cognitect.aws.client.api :as aws]))

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

(defmulti format-result
          "Returns a dispatch-value based on the response received from the aws service"
          (fn [{:keys [ErrorResponse]}]
            (cond
              (not (nil? ErrorResponse)) :error
              :else :success)))

(defmethod format-result :error
  [result]
  {:result/error (get-in result [:ErrorResponse :Error :Message])})

(defmethod format-result :success
  [{{:keys [Path GroupName GroupId Arn CreateDate]} :Group} result]
  (format "Group %s/%s with Id %s and Arn %s has been created successfully on %s"
          Path GroupName GroupId Arn CreateDate))

(defn iam-create-group
  "Creates an aws IAM group"
  [group-name]
  (let [response (aws/invoke iam {:op      :CreateGroup
                                  :request {:GroupName group-name}})]
    (format-result response)))