(ns yetibot.test.integration.alias-render-data
  (:require [clojure.string :as str]
            [midje.sweet :refer [fact =>]]
            [yetibot.commands.weather :as weather]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.commands.collections]
            [yetibot.core.commands.render]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.public-result :as public]))

(def locations ["edmonton" "59106" "nyc" "sunnyvale" "48178"])
(def temperatures [(- 1.0) 3.0 18.0 10.0 13.0])
(def rows
  (mapv (fn [city temp]
          {:city_name city :state_code "State" :country_code "US"
           :temp temp :app_temp temp :rh 60 :aqi 28
           :wind_spd 5 :wind_cdir "SW"
           :weather {:description "Clear" :private "PRIVATE-NESTED"}
           :private "PRIVATE-WEATHER"}) locations temperatures))

(def quicktemp
  (str "weather $s | render "
       "{{temp|multiply:1.8|add:32|round|number-format:%02d}}°F / "
       "{{temp}}°C , RH {{rh}}%, AQI {{aqi}} - {{weather.description}}, "
       "{{city_name}}, {{state_code}} {{country_code}} "
       "{{country_code|lower|yetibot:flag}}"))

(defn flag-fixture [_] "🇺🇸")

(fact "the actual nested temps aliases keep each weather record with its row"
  (try (deref alias/loader) (catch Exception _ nil))
  (let [calls (atom []) seen (atom [])
        by-city (zipmap locations rows)]
    (with-redefs [hooks/hooks (atom @hooks/hooks)
                  hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                  channel/settings-for-chat-source (constantly {})
                  handler/add-user-message-to-history (fn [& _])
                  history/add (fn [& _])
                  weather/current (fn [loc]
                                    (swap! calls conj loc)
                                    {:data [(get by-city loc)]})]
      ;; Weather is the real command. Only transport and the unrelated local
      ;; flag helper are fixtures; alias/handler/parser/render/xargs/sort aren't.
      (hooks/cmd-hook #"weather" #".+" weather/weather-cmd)
      (hooks/cmd-hook #"flag" _ flag-fixture)
      (doseq [[name body] [["locs" "list edmonton 59106 nyc sunnyvale 48178"]
                          ["quicktemp" quicktemp]
                          ["temps" "locs | xargs quicktemp | sort"]]]
        (#'alias/wire-alias {:cmd-name name :cmd body}))
      (let [result (binding [public/*capture* #(swap! seen conj %)]
                     (handler/handle-parsed-expr
                       {:adapter :discord :uuid :test :room "test"}
                       {:id "u" :username "u"} {:id "bot"}
                       (parser/parser "temps")))
            snapshots (mapv :public-data (filter :public-data? @seen))
            expected (mapv rows [0 1 3 4 2])]
        (count (:value result)) => 5
        (mapv #(first (str/split % #"°F")) (:value result))
        => ["30" "37" "50" "55" "64"]
        (:data result) => expected
        (count snapshots) => 1
        (mapv #(get-in % [:current :city_name]) (first snapshots))
        => ["edmonton" "59106" "sunnyvale" "48178" "nyc"]
        (mapv #(get-in % [:current :temp]) (first snapshots))
        => [(- 1.0) 3.0 10.0 13.0 18.0]
        (every? #(= "C" (get-in % [:units :temperature])) (first snapshots))
        => true
        (str/includes? (pr-str snapshots) "PRIVATE") => false
        (frequencies @calls) => (zipmap locations (repeat 1))))))
