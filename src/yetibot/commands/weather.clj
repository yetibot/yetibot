(ns yetibot.commands.weather
  (:require
    [schema.core :as sch]
    [clojure.string :as str]
    [clj-http.client :as client]
    [yetibot.core.util.http :refer [encode]]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.models.postal-code :refer [chk-postal-code]]))

(def config (:value (get-config sch/Any [:weather :weatherbitio])))

(def api-key (:key config))
(def default-zip (-> config :default :zip))

(defn get-json
  [uri]
  (try
    (let [{:keys [status body]} (client/get uri {:as :json :coerce :always})]
      (condp = status
        200 (first (:data body))
        204 {:error "Location not found."}))
    (catch Exception e
      (let [{:keys [status body]} (ex-data e)]
        (error "Request failed with status:" status)
        body))))

(defn- endpoint [repr q]
  (format "https://api.weatherbit.io/v2.0/%s?key=%s&units=M&%s" repr api-key q))

(defn- current-by-name
  "Get current conditions by location name str"
  [name]
  (get-json (endpoint "current" (format "city=%s" (encode name)))))

(defn- current-by-pc
  "Get current conditions by post code and country code"
  [pc cc]
  (get-json (endpoint "current" (apply format "postal_code=%s&country=%s"
                                       (map encode [pc cc])))))

(defn- error-response [c] (:error c))

(defn c-to-f [c] (-> (* c 9/5) (+ 32) float))
(defn km-to-mi [km] (-> (/ km 1.609) float))

(defn get-units
  [cc]
  (let [cc (-> cc str/lower-case keyword)]
    (condp = cc
      ;; Liberia
      :lbr {:temp  {:sym "F"   :conv c-to-f}
            :speed {:sym "mph" :conv km-to-mi}}

      ;; Myanmar
      :mm  {:temp  {:sym "F"   :conv c-to-f}
            :speed {:sym "mph" :conv km-to-mi}}

      ;; The United States of America
      :us  {:temp  {:sym "F"   :conv c-to-f}
            :speed {:sym "mph" :conv km-to-mi}}

      ;; THE ENTIRE REST OF THE WORLD
      {:temp  {:sym "C"    :conv float}
       :speed {:sym "km/h" :conv float}})))

(defn- l10n-value
  [v u cc]
  (let [{:keys [sym conv]} (get (get-units cc) u)]
    [(conv v) sym]))

(defn- l10n-temp [temp cc] (l10n-value temp :temp cc))

(defn- l10n-speed [speed cc] (l10n-value speed :speed cc))

(defn- fmt-location-title
  [{:keys [city_name state_code country_code]}]
  (let [loc (if (re-matches #"\d+" state_code)
              city_name
              (str city_name ", " state_code))]
    (format "Current conditions for %s (%s):" loc country_code)))

(defn- fmt-description
  [{cc :country_code temp :temp {:keys [icon code description]} :weather}]
  (let [[temp unit] (l10n-temp temp cc)]
    (format "%.1f°%s - %s"
            temp unit
            (str/join (map str/capitalize (str/split description #"\b"))))))

(defn- fmt-feels-like
  [{cc :country_code app_temp :app_temp}]
  (let [[app_temp unit] (l10n-temp app_temp cc)]
    (format "Feels like %d°%s"
            (-> app_temp float Math/round)
            unit)))

(defn- fmt-wind
  [{cc :country_code :keys [wind_spd wind_cdir]}]
  (let [[wind_spd unit] (l10n-speed wind_spd cc)]
    (format "Winds %.1f %s from %s"
            wind_spd unit wind_cdir)))

(defn- format-current
  [c]
  (map #(% c) [fmt-location-title
               fmt-description
               fmt-feels-like
               fmt-wind]))

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
