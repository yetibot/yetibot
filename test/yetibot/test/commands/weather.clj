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

(facts "about fomatting fns"
       (fact fmt-location-title
             (fmt-location-title loc-nyc) => "New York, NY (US)"
             (fmt-location-title loc-bcr) => "Bucharest (RO)")
       (fact fmt-description
             (fmt-description loc-nyc) => "32.0°F - Titlecase Me"
             (fmt-description loc-bcr) => "50.0°C - Titlecase Me")
       (fact fmt-feels-like
             (fmt-feels-like loc-nyc) => "Feels like 77°F"
             (fmt-feels-like loc-bcr) => "Feels like 100°C")
       (fact fmt-wind
             (fmt-wind loc-nyc) => "Winds 6.2 mph N"
             (fmt-wind loc-bcr) => "Winds 10.0 km/h SSE"))
