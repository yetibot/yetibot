(ns yetibot.api.s3
  (:require [aws.sdk.s3 :as s3]
            [clojure.string :as s])
  (:use [yetibot.util :only (env)]))

(def cred {:access-key (:AWS_ACCESS_KEY env), :secret-key (:AWS_SECRET_KEY env)})

(defn content [path]
  (let [[bucket key] (s/split path #"\/" 2)]
    (slurp (:content (s3/get-object cred bucket key)))))
