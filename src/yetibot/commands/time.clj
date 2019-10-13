(ns yetibot.commands.time
  (:require
   [yetibot.core.hooks :refer [cmd-hook]]
   [clj-time.core :as time]
   [clj-time.format :as f]))

(def date-time-formatter
  (-> (f/formatter "yyyy-MM-dd HH:mm:ss")
      (f/with-zone (time/default-time-zone))))

(defn list-timezones
  "time zones # list known timezones"
  [_] (vec (time/available-ids)))

(defn format-with [fmt]
  (f/unparse fmt (time/now)))

(defn time-with-offset
  "time +-<offset> # report current time with given UTC offset"
  [{[_ offset-str] :match}]
  (->> (read-string offset-str)
       (time/time-zone-for-offset)
       (f/with-zone date-time-formatter)
       (format-with)))

(defn time-default
  "time # report current time with server timezone"
  [_] (format-with date-time-formatter))

(defn time-with-zoneid
  "time <zoneid> # report current time with given zone"
  [{[_ zone-id] :match}]
  (->> zone-id
       (time/time-zone-for-id)
       (f/with-zone date-time-formatter)
       (format-with)))

(cmd-hook #"time"
          #"zones" list-timezones
          #"([+-]\d+)" time-with-offset
          #"(.+)" time-with-zoneid
          _ time-default)
