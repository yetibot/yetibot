(ns yetibot.commands.weather
  (:require
    [schema.core :as sch]
    [clojure.string :as str]
    [yetibot.core.util.http :refer [get-json fetch encode map-to-query-string]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.models.postal-code :refer [chk-postal-code]]))

(def config (:value (get-config sch/Any [:weather :weatherbitio])))

(def api-key (:key config))
(def default-zip (-> config :default :zip))

(defn- endpoint-base [repr q]
  (format "https://api.weatherbit.io/v2.0/%s?key=%s&%s" repr api-key q))

(defn- current-by-name
  "Get current conditions by location name str"
  [name]
  (get-json (endpoint "current" (format "city=%s" (encode name)))))

(defn- current-by-pc
  "Get current conditions by post code and country code"
  [pc cc]
  (get-json (endpoint "current" (apply format "postal_code=%s&country=%s" (map encode [pc cc])))))

(defn- error-response [c] (:error c))

(defn- format-current [c]
  (when (contains? c :data)
    (let [loc (-> c :data (get 0))]
      [(format "Current conditions for %s:" (:city_name loc))
       (format "%s, %s" (:temp loc) (-> loc :weather :description))
       (format "Feels like %s" (:app_temp loc))
       (format "Wind: %s from the %s" (:wind_spd loc) (:wind_cdir_full loc))
       (format "Precip: %s" (or (:precip loc) "none"))
       ])))

(defn current
  [s]
  (if-let [[pc cc] (apply chk-postal-code (str/split s #"\s*,\s*"))]
    (current-by-pc pc cc)
    (current-by-name s)))

(defn weather-cmd
  "weather <location> # look up current weather for <location>"
  {:yb/cat #{:info}}
  [{:keys [match]}]
  (let [cs (current match)]
    (or
      (error-response cs)
      (format-current cs))))

(defn default-weather-cmd
  "weather # look up weather for default location"
  {:yb/cat #{:info}}
  [_] (weather-cmd {:match default-zip}))

(cmd-hook ["weather" #"^weather$"]
          #".+" weather-cmd
          _ default-weather-cmd)
