(ns yetibot.test.commands.weather
  (:require
   [midje.sweet :refer [facts fact =>]]
   [yetibot.commands.weather :refer :all]))

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

(def formatters-us (get-formatters nil (:country_code loc-nyc)))
(def formatters-us-metric (get-formatters :c (:country_code loc-nyc)))

(def formatters-ro (get-formatters nil (:country_code loc-bcr)))
(def formatters-ro-imperl (get-formatters :f (:country_code loc-bcr)))

(facts "about fomatting fns"
       (fact fmt-location-title
             (fmt-location-title loc-nyc) => "New York, NY (US)"
             (fmt-location-title loc-bcr) => "Bucharest (RO)")
       (fact fmt-description
             (fmt-description formatters-us loc-nyc)        => "32.0°F - Titlecase Me"
             (fmt-description formatters-us-metric loc-nyc) => "0.0°C - Titlecase Me"
             (fmt-description formatters-ro loc-bcr)        => "50.0°C - Titlecase Me"
             (fmt-description formatters-ro-imperl loc-bcr) => "122.0°F - Titlecase Me")
       (fact fmt-feels-like
             (fmt-feels-like formatters-us  loc-nyc) => "Feels like 77.0°F"
             (fmt-feels-like formatters-ro loc-bcr) => "Feels like 100.0°C")
       (fact fmt-wind
             (fmt-wind formatters-us loc-nyc)        => "Winds 6.2 mph N"
             (fmt-wind formatters-us-metric loc-nyc) => "Winds 10.0 km/h N"
             (fmt-wind formatters-ro loc-bcr)        => "Winds 10.0 km/h SSE"
             (fmt-wind formatters-ro-imperl loc-bcr) => "Winds 6.2 mph SSE"))
