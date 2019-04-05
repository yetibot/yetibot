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
