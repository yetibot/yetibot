(ns yetibot.commands.weather
  (:require [yetibot.util.http :refer [get-json]]
            [yetibot.util :refer [env]]
            [yetibot.hooks :refer [cmd-hook]]))

(def api-key (:WUNDERGROUND_API_KEY env))
(def default-loc (:WEATHER_DEFAULT_LOCATION env))

(defn- conditions [loc]
  (get-json (format "http://api.wunderground.com/api/%s/conditions/q/%s.json"
                    api-key loc)))

(defn prn-keys [coll ks] (map (fn [k] (str k ": " (k coll) "\n")) ks))

(defn- format-conditions [c]
  (let [co (:current_observation c)
        loc (:observation_location co)]
    [(format "Current conditions for %s:" (:full loc))
     (:temperature_string co) 
     (format "Feels like: %s" (:feelslike_string co))
     (format "Windchill: %s" (:windchill_string co))
     (format "Wind: %s" (:wind_string co))
     (format "Precip today: %s" (:precip_today_string co))
     (format "Precip last hour: %s" (:precip_1hr_string co))
     ]))

(defn weather-cmd
  "weather <location> # look up current weather for <location>"
  [{:keys [match]}]
  (format-conditions (conditions match)))

(defn default-weather-cmd
  "weather # look up weather for default location"
  [_] (weather-cmd {:match default-loc}))

(cmd-hook #"weather"
          #".+" weather-cmd
          _ default-weather-cmd)
