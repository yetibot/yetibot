(ns yetibot.commands.weather
  (:require
    [schema.core :as sch]
    [clojure.string :as str]
    [clj-http.client :as http.client]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.models.postal-code :refer [chk-postal-code]]
    [yetibot.commands.weather.formatters :as fmt]))

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
  (let [[_ pc cc] (re-matches #"(.+?)(?:,\s*([^,]+))?" (str loc))]
    (if (nil? cc)
      (if-let [[pc cc] (chk-postal-code pc)]
        (get-by-pc path pc cc)
        (get-by-name path loc))
      (if-let [[pc cc] (chk-postal-code pc cc)]
        (get-by-pc path pc cc)
        (get-by-name path loc)))))

(defn- error-response [{:keys [error status_code status_message]}]
  (cond
    error {:result/error error}
    (= 429 status_code) {:result/error status_message}))

(defn- format-current
  [formatters c]
  (cons (fmt/location-title c)
        (map #(% formatters c) [fmt/description
                                fmt/feels-like
                                fmt/wind])))

(defn current
  [loc]
  (get-by-loc "current" loc))

(defn forecast
  [loc]
  (get-by-loc "forecast/daily" loc))

(defn parse-args
  "parse args to vec of unit kw and args str"
  [s]
  (let [[_ unit args] (re-matches #"(?i)(?:\s*(-[micf]))?\s*(.+)", s)
        unit (when-not (nil? unit)
               (if (or (= unit "-i") (= unit "-f")) :i :m))]
    [unit args]))

(defn weather-cmd
  "weather <location> # look up current weather for <location> by name or postal code, optional country code, -c or -f to force units"
  {:yb/cat #{:info}}
  [{:keys [match]}]
  (let [[unit loc] (parse-args match)
        result (current loc)]
    (or
      (error-response result)
      (let [{[cs] :data} result
            formatters (fmt/get-formatters unit (:country_code cs))]
        {:result/value (format-current formatters cs)
         :result/data cs}))))

(defn default-weather-cmd
  "weather # look up weather for default location"
  {:yb/cat #{:info}}
  [_]
  (if default-zip
    (weather-cmd {:match default-zip})
    {:result/error "A default zip code is not configured.
                    Configure it at path weather.weatherbitio.default.zip"}))

(defn forecast-cmd
  "weather forecast <location> # look up forecast for <location> by name or postal code, optional country code, -c or -f to force units"
  {:yb/cat #{:info}}
  [{[_ match] :match}]
  (let [[unit loc] (parse-args match)
        result (forecast loc)]
    (or
      (error-response result)
      (let [{:keys [city_name country_code data]} result
            formatters (fmt/get-formatters unit country_code)
            location (fmt/location-title result)]
        {:result/value (into [location]
                             (map
                               (partial fmt/forecast-item formatters)
                               data))
         :result/data result}))))

(cmd-hook #"weather"
  #"forecast\s+(.+)" forecast-cmd
  #".+" weather-cmd
  _ default-weather-cmd)
