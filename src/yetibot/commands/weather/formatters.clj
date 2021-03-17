(ns yetibot.commands.weather.formatters
  (:require
   [clojure.string :as str]))

(defn c-to-f [c] (-> (* c 9/5) (+ 32) float))

(defn km-to-mi [km] (-> (/ km 1.609) float))

(def imperial-units
  {:temp  (fn [v] (format "%.1f°%s" (c-to-f v) "F"))
   :speed (fn [v] (format "%.1f %s" (km-to-mi v) "mph"))})

(def metric-units
  {:temp  (fn [v] (format "%.1f°%s" (float v) "C"))
   :speed (fn [v] (format "%.1f %s" (float v) "km/h"))})

(defn- get-formatters-by-cc
  [cc]
  (let [cc (-> cc str/lower-case keyword)]
    (condp = cc
      :lbr imperial-units  ;; Liberia
      :mm  imperial-units  ;; Myanmar
      :us  imperial-units  ;; The United States of America
      ;; THE ENTIRE REST OF THE WORLD
      metric-units)))

(defn- fmt-description
  [s]
  (str/join (map str/capitalize (str/split s #"\b"))))

(defn get-formatters
  [unit cc]
  (if (nil? unit)
    (get-formatters-by-cc cc)
    (if (= unit :i)
      imperial-units
      metric-units)))

(defn location-title
  [{:keys [city_name state_code country_code]}]
  (let [loc (if (re-matches #"\d+" state_code)
              city_name
              (str city_name ", " state_code))]
    (format "%s (%s)" loc country_code)))

(defn summary
  [{fmt :temp} {temp :temp {description :description} :weather}]
  (format "%s - %s"
          (fmt temp)
          (fmt-description description)))

(defn feels-like
  [{fmt :temp} {app_temp :app_temp}]
  (format "Feels like %s" (fmt app_temp)))

(defn wind
  [{fmt :speed} {:keys [wind_spd wind_cdir]}]
  (format "Winds %s %s" (fmt wind_spd) wind_cdir))

(defn forecast-item
  "Format a forecast item like: date: min - max"
  [{fmt :temp} {:keys [min_temp max_temp valid_date weather]}]
  (format "%s: %s - %s, %s"
          valid_date
          (fmt min_temp)
          (fmt max_temp)
          (fmt-description (:description weather))))

(defn- keyword->name
  "Utility function returning gas name from keywordized gas-name
  (e.g : :no2 -> NO2)"
  [k]
  (-> k name clojure.string/upper-case))

(defn- pm-size
  [pm-type]
  (condp = pm-type
    :pm10 10
    :pm25 2.5))

(defonce pollen-origin
  {:pollen_level_tree "Tree"
   :pollen_level_weed "Weed"
   :pollen_level_grass "Grass"})

(defonce level
  {0 "None"
   1 "Low"
   2 "Moderate"
   3 "High"
   4 "Very high"})

(defmulti air-quality-item
  (fn [[k v]]
    (cond
      (#{:aqi}  k) :air-quality-index
      (#{:o3 :so2 :no2 :co} k) :gas
      (#{:pm10 :pm25} k) :particulate
      (#{:pollen_level_tree :pollen_level_weed :pollen_level_grass} k) :pollen-level
      (#{:predominant_pollen_type} k) :predominant-pollen-type
      (#{:mold_level} k) :mold-level)))

(defmethod air-quality-item :air-quality-index
  [[_ v]]
  (format "Air quality index is: %s" v))

(defmethod air-quality-item :gas
  [[gas-name v]]
  (format "Conc. of %s is %s (µg/m³)" (keyword->name gas-name) v))

(defmethod air-quality-item :particulate
  [[pm-type v]]
  (format "Conc. of particulate matter < %s microns: %s (µg/m³)" (pm-size pm-type) v))

(defmethod air-quality-item :pollen-level
  [[p-type v]]
  (let [p-origin (pollen-origin p-type)
        level (level v)]
    (format "%s pollen level: %s (%s)" p-origin level v)))

(defmethod air-quality-item :predominant-pollen-type
  [[_ v]]
  (format "Predominant pollen type: %s" v))

(defmethod air-quality-item :mold-level
  [[_ v]]
  (format "Mold level: %s" (level v)))
