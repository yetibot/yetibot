(ns yetibot.commands.weather
  (:require
    [clojure.string :refer [join]]
    [yetibot.core.util.http :refer [get-json fetch encode map-to-query-string]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [config-for-ns conf-valid?]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def config (config-for-ns))

(def api-key (:wunderground-api-key config))
(def default-zip (:default-zip config))
(defn tee [s]
  (prn s)
  s)

(defn endpoint [url & args]
  (tee (str "http://api.wunderground.com/api/" api-key
            (apply format url args))))

(defn loc-endpoint [api loc]
  (endpoint "%s/q/%s.json" api (encode loc)))

(defn- conditions [loc]
  (let [url (endpoint "/conditions/q/%s.json" (encode loc))]
    (get-json url)))

(defn- cams [loc]
  (let [url (endpoint "/webcams/q/%s.json" (encode loc))]
    (get-json url)))

(defn- forecast [loc] (get-json (loc-endpoint "/forecast" loc)))

(defn- satellite [loc]
  (str (endpoint "/animatedradar/animatedsatellite/q/%s.gif" (encode loc))
       "?" (map-to-query-string {:num 10})))

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
      [(format "Current conditions for %s elevation %s:" (:full loc) (:elevation loc))
       (format "%s, %s" (:temperature_string co) (:weather co))
       (format "Feels like: %s" (:feelslike_string co))
       (format "Windchill: %s" (:windchill_string co))
       (format "Wind: %s" (:wind_string co))
       (format "Precip last hour: %s" (:precip_1hr_string co))
       ])))

(defn- format-webcams [res]
  (when-let [cams (:webcams res)]
    (map (juxt (fn [c] (str (:CURRENTIMAGEURL c) "&.jpg")) :neighborhood) cams)))

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

(defn cams-cmd
  "weather cams <location> # find web cams in <location>"
  [{[_ loc] :match}]
  (let [res (cams loc)]
    (or (error-response res)
        (multiple-results res)
        (format-webcams res))))

(defn satellite-cmd
  "weather satellite <location> # look up satellite image for <location>"
  [{[_ loc] :match}]
  ; TODO: validate the loc. Currently this will 500 if the loc is not valid.
  (satellite loc))

(if (conf-valid?)
  (cmd-hook ["weather" #"^weather$"]
            #"cams\s+(.+)" cams-cmd
            #".+" weather-cmd
            _ default-weather-cmd)
  (info "Weather is not configured"))
