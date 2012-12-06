(ns yetibot.api.s3
  (:require [aws.sdk.s3 :as s3]
            [clojure.string :as s])
  (:use [yetibot.util :only (env)]))

(def cred {:access-key (:AWS_ACCESS_KEY env), :secret-key (:AWS_SECRET_KEY env)})

(defn content [path]
  (let [[bucket key] (s/split path #"\/" 2)]
    (slurp (:content (s3/get-object cred bucket key)))))

(defn put [path object]
  (let [[bucket key] (s/split path #"\/" 2)]
    (s3/put-object cred bucket key object)))

(defn buckets []
  (s3/list-buckets cred))

(defn list-objects [bucket prefix]
  (s3/list-objects cred bucket {:delimiter "/" :prefix prefix}))
