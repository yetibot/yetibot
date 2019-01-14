(ns yetibot.api.aws
  (:require
    [yetibot.core.schema :refer [non-empty-str]]
    [schema.core :as sch]
    [yetibot.core.config :refer [get-config]]
    [yetibot.api.aws :as aws]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def aws-schema
  {:aws-access-key-id non-empty-str
   :aws-secret-access-key non-empty-str
   (sch/optional-key :region) non-empty-str})

(defn config
  "Returns AWS-related configuration"
  []
  (:value (get-config aws-schema [:aws])))

(defn configured? [] (config))

(defn echo-test
  "Returns an echo for testing purpose"
  []
  (format "hey dude what's up!"))

(when (aws/configured?)
  (cmd-hook ["aws" #"aws"]
            #"echo" echo-test))