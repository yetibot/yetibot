(ns yetibot.api.s3
  (:require
    [schema.core :as sch]
    [aws.sdk.s3 :as s3]
    [clojure.string :as s]
    [yetibot.core.config :refer [get-config]]))

(def s3-schema
  {:access {:key sch/Str}
   :secret {:key sch/Str}})

(defn config [] (get-config s3-schema [:s3]))

(defn transformed-config []
  (when-let [c (:value (config))]
    {:access-key (-> c :access :key)
     :secret-key (-> c :secret :key)}))

(defn content [path]
  (let [[bucket key] (s/split path #"\/" 2)]
    (slurp (:content (s3/get-object (transformed-config) bucket key)))))

(defn put [path object]
  (let [[bucket key] (s/split path #"\/" 2)]
    (s3/put-object (transformed-config) bucket key object)))

(defn buckets []
  (s3/list-buckets (transformed-config)))

(defn list-objects [bucket prefix]
  (s3/list-objects (transformed-config)
                   bucket
                   {:delimiter "/" :prefix prefix}))
