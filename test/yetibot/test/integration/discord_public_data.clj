(ns yetibot.test.integration.discord-public-data
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [midje.sweet :refer [fact =>]]
            [yetibot.commands.weather :as weather]
            [yetibot.commands.release :as release]
            [yetibot.core.util.public-result :as public-result]))

(def current-weather
  {:city_name "Seattle" :state_code "WA" :country_code "US"
   :temp 21.0 :app_temp 22.0
   :wind_spd 5.0 :wind_cdir "SW" :rh 60 :aqi 20
   :weather {:description "Sunny" :private "PRIVATE-WEATHER"}
   :private "PRIVATE-DATA" :headers {:authorization "PRIVATE-KEY"}})

(defn- projection [result]
  (let [seen (atom [])]
    (binding [public-result/*capture* #(swap! seen conj %)]
      (public-result/observe! result))
    (first @seen)))

(fact "weather publishes an explicit field projection without altering pipe data"
  (with-redefs [weather/current (constantly {:data [current-weather]})]
    (let [result (weather/weather-cmd {:match "Seattle"})
          public (projection result)]
      (:result/data result) => current-weather
      (:public-data? public) => true
      (get-in public [:public-data :current :temp]) => 21.0
      (get-in public [:public-data :units :temperature]) => "C"
      (str/includes? (pr-str public) "PRIVATE-") => false)))

(fact "forecast projects each day and omits unknown nested fields"
  (with-redefs [weather/forecast
                (constantly
                  {:city_name "Seattle" :state_code "WA" :country_code "US"
                   :private "PRIVATE-DATA"
                   :data [{:min_temp 12 :max_temp 21 :valid_date "2026-11-03"
                           :weather {:description "Sunny" :private "PRIVATE-DATA"}
                           :private "PRIVATE-DATA"}]})]
    (let [result (weather/forecast-cmd {:match ["" "Seattle"]})
          public (projection result)]
      (get-in public [:public-data :forecast 0 :max_temp]) => 21
      (str/includes? (pr-str public) "PRIVATE-") => false)))

(fact "forecast location is valid without the optional state code"
  (with-redefs [weather/forecast
                (constantly {:city_name "Seattle" :country_code "US"
                             :data [{:min_temp 12 :max_temp 21
                                     :valid_date "2026-11-03"
                                     :weather {:description "Sunny"}}]})]
    (first (:result/value
             (weather/forecast-cmd {:match ["" "Seattle"]})))
    => "Seattle (US)"))

(fact "HTTP and transport failures retain safe stages, never provider bodies"
  (doseq [get! [(fn [& _] {:status 503
                           :body {:error "PRIVATE-BODY"
                                  :headers {:authorization "PRIVATE-KEY"}}})
               (fn [& _] (throw (ex-info "PRIVATE-EXCEPTION"
                                        {:status 503 :body "PRIVATE-BODY"})))]]
    (with-redefs [http/get get!]
      (let [result (weather/weather-cmd {:match "Seattle"})
            public (projection result)]
        (:error? public) => true
        (get-in public [:public-error :http-status]) => 503
        (get-in public [:public-error :stage]) => "weather lookup"
        (str/includes? (pr-str result) "PRIVATE-") => false)))
  (with-redefs [http/get (fn [& _] (throw (Exception. "PRIVATE-ENDPOINT")))]
    (let [public (projection (weather/weather-cmd {:match "Seattle"}))]
      (get-in public [:public-error :kind]) => "transport"
      (get-in public [:public-error :http-status]) => nil)))

(fact "invalid JSON-shaped successes and private error fields are not reflected"
  (with-redefs [http/get (fn [& _] {:status 200
                                   :body {:error "PRIVATE-ERROR"}})]
    (let [result (weather/weather-cmd {:match "Seattle"})
          public (projection result)]
      (:error? public) => true
      (get-in public [:public-error :kind]) => "invalid-response"
      (str/includes? (pr-str result) "PRIVATE-") => false)))

(fact "version and changelog expose the already-public release projection"
  (let [version (projection (release/version-cmd {}))
        changelog (projection (release/changelog-cmd {:match ["" ""]}))]
    (:public-data? version) => true
    (get-in version [:public-data :build])
    => (get-in (release/version-cmd {}) [:result/data :build])
    (:public-data? changelog) => true
    (seq (get-in changelog [:public-data :changes])) => seq))
