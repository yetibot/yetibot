(ns yetibot.test.commands.weather
  (:require
   [midje.sweet :refer [facts fact =>]]
   [yetibot.commands.weather :refer :all]
   [yetibot.commands.weather.formatters :as fmt]))

(def loc-nyc
  {:city_name "New York"
   :state_code "NY"
   :country_code "US"
   :temp 0
   :app_temp 25
   :weather {:icon nil :code nil :description "titlecase me"}
   :wind_spd 10
   :wind_cdir "N"})

(def loc-bcr
  {:city_name "Bucharest"
   :state_code "10"
   :country_code "RO"
   :temp 50
   :app_temp 100
   :weather {:icon nil :code nil :description "TITLECASE ME"}
   :wind_spd 10
   :wind_cdir "SSE"})

(def formatters-us (fmt/get-formatters nil (:country_code loc-nyc)))
(def formatters-us-metric (fmt/get-formatters :m (:country_code loc-nyc)))

(def formatters-ro (fmt/get-formatters nil (:country_code loc-bcr)))
(def formatters-ro-imperl (fmt/get-formatters :i (:country_code loc-bcr)))

(facts "about fomatting fns"
       (fact fmt/location-title
             (fmt/location-title loc-nyc) => "New York, NY (US)"
             (fmt/location-title loc-bcr) => "Bucharest (RO)")
       (fact fmt/summary
             (fmt/summary formatters-us loc-nyc)        => "32.0°F - Titlecase Me"
             (fmt/summary formatters-us-metric loc-nyc) => "0.0°C - Titlecase Me"
             (fmt/summary formatters-ro loc-bcr)        => "50.0°C - Titlecase Me"
             (fmt/summary formatters-ro-imperl loc-bcr) => "122.0°F - Titlecase Me")
       (fact fmt/feels-like
             (fmt/feels-like formatters-us  loc-nyc) => "Feels like 77.0°F"
             (fmt/feels-like formatters-ro loc-bcr)  => "Feels like 100.0°C")
       (fact fmt/wind
             (fmt/wind formatters-us loc-nyc)        => "Winds 6.2 mph N"
             (fmt/wind formatters-us-metric loc-nyc) => "Winds 10.0 km/h N"
             (fmt/wind formatters-ro loc-bcr)        => "Winds 10.0 km/h SSE"
             (fmt/wind formatters-ro-imperl loc-bcr) => "Winds 6.2 mph SSE")
       (fact fmt/air-quality-item
             (fmt/air-quality-item [:aqi 301]) => "Air quality index is: 301"
             (fmt/air-quality-item [:no2 58.407]) => "Conc. of NO2 is 58.407 (µg/m³)"
             (fmt/air-quality-item [:o3 89.8]) => "Conc. of O3 is 89.8 (µg/m³)"
             (fmt/air-quality-item [:co 230.844]) => "Conc. of CO is 230.844 (µg/m³)"
             (fmt/air-quality-item [:so2 0.713393]) => "Conc. of SO2 is 0.713393 (µg/m³)"
             (fmt/air-quality-item [:pollen_level_tree 1]) => "Tree pollen level: Low (1)"
             (fmt/air-quality-item [:pollen_level_weed 1]) => "Weed pollen level: Low (1)"
             (fmt/air-quality-item [:pollen_level_grass 1]) => "Grass pollen level: Low (1)"
             (fmt/air-quality-item [:pm10 424.599]) => "Conc. of particulate matter < 10 microns: 424.599 (µg/m³)"
             (fmt/air-quality-item [:pm25 71.2521]) => "Conc. of particulate matter < 2.5 microns: 71.2521 (µg/m³)"
             (fmt/air-quality-item [:predominant_pollen_type "Molds"]) => "Predominant pollen type: Molds"
             (fmt/air-quality-item [:mold_level 1]) => "Mold level: Low (1)"))
