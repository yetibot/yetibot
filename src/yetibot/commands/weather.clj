(ns yetibot.commands.weather
  (:require
    [clojure.spec.alpha :as s]
    [clj-http.client :as http.client]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.commands.weather.formatters :as fmt]))

(s/def ::config any?)

(def config (:value (get-config ::config [:weather :weatherbitio])))

(def default-zip (-> config :default :zip))

(defn get-json
  [location]
  (try
    (let [uri (str "https://wttr.in/" (clojure.string/replace (or location "") #" " "+"))
          options {:as :json :coerce :always :query-params {:format "j1"}}
          {:keys [status body]} (http.client/get uri options)]
      (condp = status
        200 body
        {:error "Location not found."}))
    (catch Exception e
      (let [{:keys [status body]} (ex-data e)]
        (error "Request failed with status:" status)
        (or body {:error "Failed to retrieve weather data."})))))

(defn transform-current
  [res]
  (if-let [current (first (:current_condition res))]
    (let [area (first (:nearest_area res))
          city (some-> (get-in area [:areaName 0 :value]) clojure.string/trim)
          state (some-> (get-in area [:region 0 :value]) clojure.string/trim)
          raw-country (some-> (get-in area [:country 0 :value]) clojure.string/trim)
          country (if (or (= raw-country "United States of America")
                          (= raw-country "USA"))
                    "US"
                    (or raw-country ""))
          temp-c (some-> (get current :temp_C) Float/parseFloat)
          app-temp-c (some-> (get current :FeelsLikeC) Float/parseFloat)
          wind-kmh (some-> (get current :windspeedKmph) Float/parseFloat)
          wind-dir (some-> (get current :winddir16Point) clojure.string/trim)
          desc (some-> (get-in current [:weatherDesc 0 :value]) clojure.string/trim)]
      {:data [{:city_name city
               :state_code state
               :country_code country
               :temp temp-c
               :weather {:description desc}
               :app_temp app-temp-c
               :wind_spd wind-kmh
               :wind_cdir wind-dir}]})
    {:error "No current conditions found."}))

(defn transform-forecast
  [res]
  (if-let [weather-days (:weather res)]
    (let [area (first (:nearest_area res))
          city (some-> (get-in area [:areaName 0 :value]) clojure.string/trim)
          state (some-> (get-in area [:region 0 :value]) clojure.string/trim)
          raw-country (some-> (get-in area [:country 0 :value]) clojure.string/trim)
          country (if (or (= raw-country "United States of America")
                          (= raw-country "USA"))
                    "US"
                    (or raw-country ""))
          forecast-data (mapv (fn [day]
                                (let [min-temp (some-> (:mintempC day) Float/parseFloat)
                                      max-temp (some-> (:maxtempC day) Float/parseFloat)
                                      valid-date (:date day)
                                      hourly (:hourly day)
                                      noon-hour (first (filter #(= (:time %) "1200") hourly))
                                      selected-hour (or noon-hour (first hourly))
                                      desc (some-> (get-in selected-hour [:weatherDesc 0 :value]) clojure.string/trim)]
                                  {:min_temp min-temp
                                   :max_temp max-temp
                                   :valid_date valid-date
                                   :weather {:description desc}}))
                              weather-days)]
      {:city_name city
       :state_code state
       :country_code country
       :data forecast-data})
    {:error "No forecast data found."}))

(defn- error-response [result]
  (cond
    (nil? result) {:result/error "No response from weather service."}
    (:error result) {:result/error (:error result)}
    :else nil))

(defn- format-current
  [formatters c]
  (cons (fmt/location-title c)
        (map #(% formatters c) [fmt/summary
                                fmt/feels-like
                                fmt/wind])))

(defn current
  [loc]
  (let [res (get-json loc)]
    (if (:error res)
      res
      (transform-current res))))

(defn forecast
  [loc]
  (let [res (get-json loc)]
    (if (:error res)
      res
      (transform-forecast res))))

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

(defn air-cmd
  "weather air <location> # look up current air conditions for <location> by name or postal code, optional country code"
  {:yb/cat #{:info}}
  [{[_ loc] :match}]
  {:result/error "Air quality lookup is no longer supported since migrating to wttr.in."})

(defn default-air-cmd
  "weather air # look up current weather for default location"
  [_]
  {:result/error "Air quality lookup is no longer supported since migrating to wttr.in."})

(cmd-hook #"weather"
          #"air\s+(.+)" air-cmd
          #"air" default-air-cmd
          #"forecast\s+(.+)" forecast-cmd
          #".+" weather-cmd
          _ default-weather-cmd)
