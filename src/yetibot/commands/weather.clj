(ns yetibot.commands.weather
  (:require
    [schema.core :as sch]
    [clojure.string :as str]
    [clj-http.client :as http.client]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.models.postal-code :refer [chk-postal-code]]))

(def config (:value (get-config sch/Any [:weather :weatherbitio])))

(def api-key (:key config))
(def default-zip (-> config :default :zip))

(defn get-json
  [uri {:keys [query-params] :as opts}]
  (try
    (let [options (merge {:as :json :coerce :always}
                         opts
                         {:query-params (merge {:key api-key :units "M"}
                                               query-params)})
          {:keys [status body]} (http.client/get uri options)]
      (condp = status
        200 body
        204 {:error "Location not found."}))
    (catch Exception e
      (let [{:keys [status body]} (ex-data e)]
        (error "Request failed with status:" status)
        body))))

(defn- endpoint
  "API docs: https://www.weatherbit.io/api"
  [path]
  (str "https://api.weatherbit.io/v2.0/" path))

(defn- get-by-name
  "Get current conditions by location name str"
  [path city]
  (get-json (endpoint path) {:query-params {:city city}}))

(defn- get-by-pc
  "Get current conditions by post code and country code"
  [path pc cc]
  (get-json (endpoint path) {:query-params {:postal_code pc
                                            :country cc}}))
(defn- get-by-loc
  "Attempt to parse out postal code and call the corresponding get-by-name or
   get-by-pc function"
  [path loc]
  (if-let [[pc cc] (apply chk-postal-code (str/split (str loc) #"\s*,\s*"))]
    (get-by-pc path pc cc)
    (get-by-name path loc)))

(defn- error-response [{:keys [error status_code status_message]}]
  (cond
    error {:result/error error}
    (= 429 status_code) {:result/error status_message}))

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

(defn fmt-location-title
  [{:keys [city_name state_code country_code]}]
  (let [loc (if (re-matches #"\d+" state_code)
              city_name
              (str city_name ", " state_code))]
    (format "%s (%s)" loc country_code)))

(defn fmt-temp
  [temp unit]
  (format "%.1f°%s" temp unit))

(defn fmt-description
  [{cc :country_code temp :temp {:keys [icon code description]} :weather}]
  (let [[temp unit] (l10n-temp temp cc)]
    (format "%s - %s"
            (fmt-temp temp unit)
            (str/join (map str/capitalize (str/split description #"\b"))))))

(defn fmt-feels-like
  [{cc :country_code app_temp :app_temp}]
  (let [[app_temp unit] (l10n-temp app_temp cc)]
    (format "Feels like %s"
            (fmt-temp app_temp unit))))

(defn fmt-wind
  [{cc :country_code :keys [wind_spd wind_cdir]}]
  (let [[wind_spd unit] (l10n-speed wind_spd cc)]
    (format "Winds %.1f %s %s"
            wind_spd unit wind_cdir)))

(defn- format-current
  [c]
  (map #(% c) [fmt-location-title
               fmt-description
               fmt-feels-like
               fmt-wind]))

(defn current
  [loc]
  (get-by-loc "current" loc))

(defn forecast
  [loc]
  (get-by-loc "forecast/daily" loc))

(defn weather-cmd
  "weather <location> # look up current weather for <location> by name or postal code, with optional country code"
  {:yb/cat #{:info}}
  [{:keys [match]}]
  (let [result (current match)]
    (or
      (error-response result)
      (let [{[cs] :data} result]
        {:result/value (format-current cs)
         :result/data cs}))))

(defn default-weather-cmd
  "weather # look up weather for default location"
  {:yb/cat #{:info}}
  [_]
  (if default-zip
    (weather-cmd {:match default-zip})
    {:result/error "A default zip code is not configured.
                    Configure it at path weather.weatherbitio.default.zip"}))

(defn fmt-forecast-item
  "Format a forecast item like: date: min - max"
  [cc {:keys [min_temp max_temp valid_date]}]
  (format "%s: %s - %s"
          valid_date
          (apply fmt-temp (l10n-temp min_temp cc))
          (apply fmt-temp (l10n-temp max_temp cc))))

(defn forecast-cmd
  "weather forecast <location> # look up forecast for <location> by name or postal code, with optional country code"
  {:yb/cat #{:info}}
  [{[_ match] :match}]
  (let [result (forecast match)]
    (or
      (error-response result)
      (let [{:keys [city_name country_code data]} result
            location (fmt-location-title result)]
        {:result/value (into [location]
                             (map
                               (partial fmt-forecast-item country_code)
                               data))
         :result/data result}))))

(cmd-hook #"weather"
  #"forecast\s+(.+)" forecast-cmd
  #".+" weather-cmd
  _ default-weather-cmd)
