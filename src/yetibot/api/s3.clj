(ns yetibot.api.s3
  (:require
    [clojure.spec.alpha :as s]
    [aws.sdk.s3 :as s3]
    [clojure.string :as string]
    [yetibot.core.config :refer [get-config]]))

(s/def ::key string?)

(s/def ::access (s/keys :req-un [::key]))

(s/def ::secret (s/keys :req-un [::key]))

(s/def ::config (s/keys :req-un [::access ::secret]))

(defn config [] (get-config ::config [:s3]))

(defn transformed-config []
  (when-let [c (:value (config))]
    {:access-key (-> c :access :key)
     :secret-key (-> c :secret :key)}))

(defn content [path]
  (let [[bucket key] (string/split path #"\/" 2)]
    (slurp (:content (s3/get-object (transformed-config) bucket key)))))

(defn put [path object]
  (let [[bucket key] (string/split path #"\/" 2)]
    (s3/put-object (transformed-config) bucket key object)))

(defn buckets []
  (s3/list-buckets (transformed-config)))

(defn list-objects [bucket prefix]
  (s3/list-objects (transformed-config)
                   bucket
                   {:delimiter "/" :prefix prefix}))
