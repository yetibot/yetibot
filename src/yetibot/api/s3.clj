(ns yetibot.api.s3
  (:require
    [aws.sdk.s3 :as s3]
    [clojure.string :as s]
    [yetibot.core.config :refer [conf-valid? config-for-ns]]))

(def config (config-for-ns))
(def configured? (conf-valid?))

(defn content [path]
  (let [[bucket key] (s/split path #"\/" 2)]
    (slurp (:content (s3/get-object config bucket key)))))

(defn put [path object]
  (let [[bucket key] (s/split path #"\/" 2)]
    (s3/put-object config bucket key object)))

(defn buckets []
  (s3/list-buckets config))

(defn list-objects [bucket prefix]
  (s3/list-objects config bucket {:delimiter "/" :prefix prefix}))
