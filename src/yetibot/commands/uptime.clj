(ns yetibot.commands.uptime
  (:import org.apache.commons.lang3.time.DurationFormatUtils)
  (:use [yetibot.util :only(cmd-hook)]))

(defonce start-time (System/currentTimeMillis))

(defn now [] (System/currentTimeMillis))

(defn uptime-millis [] (- (now) start-time))

(defn uptime-cmd
  "uptime # list uptime in milliseconds"
  [] (DurationFormatUtils/formatDurationWords (uptime-millis) true true))

(cmd-hook #"uptime"
          #"" (uptime-cmd))
