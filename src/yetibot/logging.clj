(ns yetibot.logging
  (:require
    [taoensso.timbre
     :as timbre
     :refer [trace debug info warn error fatal spy with-log-level]]))

(timbre/set-config! [:appenders :spit :enabled?] true)
(timbre/set-config! [:shared-appender-config :spit-filename] "/var/log/yetibot/yetibot.log")

; TODO: write log to datomic
; (timbre/set-config!
;   [:appenders :my-appender]
;   {:doc       "Hello-world appender"
;    :min-level :debug
;    :enabled?  true
;    :async?    false
;    :limit-per-msecs nil ; No rate limit
;    :fn (fn [{:keys [ap-config level prefix throwable message] :as args}]
;          (when-not (:my-production-mode? ap-config)
;            (println prefix "Hello world!" message)))
