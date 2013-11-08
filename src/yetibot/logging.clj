(ns yetibot.logging
  (:require
    [yetibot.util :refer [with-fresh-db]]
    [yetibot.models.log :as log]
    [taoensso.timbre
     :as timbre
     :refer [trace debug info warn error fatal spy with-log-level]]))

(timbre/set-config! [:appenders :spit :enabled?] true)
(timbre/set-config! [:shared-appender-config :spit-filename] "/var/log/yetibot/yetibot.log")


(defn log-to-db
  [{:keys [ap-config level prefix throwable message] :as args}]
  (with-fresh-db
    (log/create (select-keys args [:level :prefix :message]))))

; log to datomic
(timbre/set-config!
  [:appenders :datomic]
  {:doc       "Datomic logger"
   :min-level :info
   :enabled?  true
   :async?    false
   :limit-per-msecs nil ; No rate limit
   :fn #'log-to-db})
