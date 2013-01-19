(ns yetibot.util
  (:require [http.async.client :as client]
            [clojure.string :as s]
            [yetibot.campfire :as cf]
            [yetibot.models.help :as help]
            [robert.hooke :as rh]
            [clojure.stacktrace :as st]
            [clojure.data.json :as json])
  (:use [clojure.contrib.cond]))

(def bot-id (str (System/getenv "CAMPFIRE_BOT_ID")))

(defmacro ensure-config [& body]
  `(when (every? identity ~'config)
     ~@body))

(def env
  (let [e (into {} (System/getenv))]
    (zipmap (map keyword (keys e)) (vals e))))
