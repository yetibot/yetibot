(ns yetibot.api.aws
  (:require
    [yetibot.core.schema :refer [non-empty-str]]
    [schema.core :as sch]
    [yetibot.core.config :refer [get-config]]
    [cognitect.aws.client.api :as aws]))

(def aws-schema
  {:aws-access-key-id non-empty-str
   :aws-secret-access-key non-empty-str
   (sch/optional-key :region) non-empty-str})

(defn config
  "Returns AWS-related configuration"
  []
  (:value (get-config aws-schema [:aws])))

(def aws-access-key-id (:aws-access-key-id (config)))
(def aws-secret-access-key (:aws-secret-access-key (config)))

(defn make-aws-client
  "Returns a aws client, given an aws service"
  [service-name]
  (aws/client {:api service-name
               :credentials-provider (cognitect.aws.credentials/basic-credentials-provider
                                       {:access-key-id aws-access-key-id
                                        :secret-access-key aws-secret-access-key})}))
; AWS clients
(def iam (make-aws-client :iam))

(defn configured? [] (config))