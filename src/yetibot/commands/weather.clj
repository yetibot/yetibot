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

(defn get-units
  [cc]
  (let [cc (-> cc str/lower-case keyword)]
    (condp = cc
      :lbr {:temp "F" :speed "mph"}  ;; Liberia
      :mm  {:temp "F" :speed "mph"}  ;; Myanmar
      :us  {:temp "F" :speed "mph"}  ;; US (come on already)
      {:temp "C" :speed "km/h"})))

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

(defn- endpoint-base [repr q]
  (format "https://api.weatherbit.io/v2.0/%s?key=%s&%s" repr api-key q))

(defn- current-by-name
  "Get current conditions by location name str"
  [name]
  (get-json (endpoint-base "current" (format "city=%s" (encode name)))))

(defn- current-by-pc
  "Get current conditions by post code and country code"
  [pc cc]
  (get-json (endpoint-base "current" (apply format "postal_code=%s&country=%s" (map encode [pc cc])))))

(defn- error-response [c] (:error c))

(defn- fmt-location-title
  [_ {:keys [city_name state_code country_code]}]
  (format "Current conditions for %s (%s):" city_name country_code))

(defn- fmt-description
  [units {temp :temp {:keys [icon code description]} :weather}]
  (format "%.1f° %s - %s"
          (float temp) (:temp units)
          (str/join "" (map str/capitalize (str/split description #"\b")))))

(defn- fmt-feels-like
  [units {app_temp :app_temp}]
  (format "Feels like %d° %s"
          (-> app_temp float Math/round) (:temp units)))

(defn- fmt-wind
  [units {:keys [wind_spd wind_cdir]}]
  (format "Winds %.1f %s from %s"
          (float wind_spd) (:speed units) wind_cdir))

(defn- format-current
  [c]
  (let [units (get-units (:country_code c))]
    (map (fn [f] (f units c))
         [fmt-location-title
          fmt-description
          fmt-feels-like
          fmt-wind])))

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
