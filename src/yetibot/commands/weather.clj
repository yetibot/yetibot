(ns yetibot.commands.weather
  (:require
    [clojure.string :refer [join]]
    [yetibot.util.http :refer [get-json encode]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.config :refer [config-for-ns conf-valid?]]
    [yetibot.hooks :refer [cmd-hook]]))

(def config (config-for-ns))

(def api-key (:wunderground-api-key config))
(def endpoint (str "http://api.wunderground.com/api/" api-key))
(def default-zip (:default-zip config))

(defn- conditions [loc]
  (let [url (format "%s/conditions/q/%s.json" endpoint (encode loc))]
    (get-json url)))

(defn- error-response [c] (-> c :response :error :description))

(defn- format-city-state-country [res]
  (if (= "USA" (:country_name res))
    (str (:city res) ", " (:state res))
    (str (:city res)
         (when (:state res) (str ", " (:state res)))
         (:country_name res))))

(defn- multiple-results [c]
  (when-let [rs (-> c :response :results)]
    (str "Found multiple locations: "
         (join "; " (map format-city-state-country rs)))))

(defn- format-conditions [c]
  (when-let [co (:current_observation c)]
    (let [loc (:observation_location co)]
      [(format "Current conditions for %s:" (:full loc))
       (:temperature_string co)
       (:weather co)
       (format "Feels like: %s" (:feelslike_string co))
       (format "Windchill: %s" (:windchill_string co))
       (format "Wind: %s" (:wind_string co))
       (format "Precip today: %s" (:precip_today_string co))
       (format "Precip last hour: %s" (:precip_1hr_string co))
       ])))

(defn weather-cmd
  "weather <location> # look up current weather for <location>"
  [{:keys [match]}]
  (let [cs (conditions match)]
    (or
      (error-response cs)
      (multiple-results cs)
      (format-conditions cs))))

(defn default-weather-cmd
  "weather # look up weather for default location"
  [_] (weather-cmd {:match default-zip}))

(if (conf-valid?)
  (cmd-hook #"weather"
            #".+" weather-cmd
            _ default-weather-cmd)
  (info "Weather is not configured"))
