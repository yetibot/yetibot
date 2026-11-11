(ns yetibot.test.integration.ai-command-capture
  (:require [clojure.string :as string]
            [midje.sweet :refer :all]
            [yetibot.commands.weather :as weather]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.commands.collections]
            [yetibot.core.commands.render]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.unparser :as unparser]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-command-tools :as commands]
            [yetibot.test.support.ai-execution :as support]
            [yetibot.util.ai-execution :as execution]))

(def locations ["edmonton" "59106" "nyc" "sunnyvale" "48178"])
(def temperatures [-1.0 3.0 18.0 10.0 13.0])
(def rows
  (mapv (fn [city temp]
          {:city_name city :state_code "State" :country_code "US"
           :temp temp :app_temp temp :rh 60 :aqi 28 :wind_spd 5 :wind_cdir "SW"
           :weather {:description "Clear" :private "PRIVATE-NESTED"}
           :private "PRIVATE-WEATHER"}) locations temperatures))
(def quicktemp
  (str "weather $s | render "
       "{{temp|multiply:1.8|add:32|round|number-format:%02d}}°F / "
       "{{temp}}°C , RH {{rh}}%, AQI {{aqi}} - {{weather.description}}, "
       "{{city_name}}, {{state_code}} {{country_code}}"))

(defn capture-temps [failed-location]
  (try (deref alias/loader) (catch Exception _ nil))
  (let [calls (atom []) sent (atom []) by-city (zipmap locations rows)]
    (with-redefs [hooks/hooks (atom @hooks/hooks)
                  hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                  channel/settings-for-chat-source (constantly {})
                  handler/add-user-message-to-history (fn [& _])
                  handler/dispatch-command-response #(swap! sent conj %)
                  history/add (fn [& _])
                  ;; This is the only data transport fixture. Real weather,
                  ;; aliases, xargs, render, sort, parser and handler run.
                  weather/current (fn [loc]
                                    (swap! calls conj loc)
                                    (if (= loc failed-location)
                                      {:error "Location unavailable."}
                                      {:data [(get by-city loc)]}))]
      (hooks/cmd-hook #"weather" #".+" weather/weather-cmd)
      (doseq [[name body] [["locs" "list edmonton 59106 nyc sunnyvale 48178"]
                          ["quicktemp" quicktemp]
                          ["temps" "locs | xargs quicktemp | sort"]]]
        (#'alias/wire-alias {:cmd-name name :cmd body}))
      (support/in-event support/request "ai chart temps"
        (fn [{:keys [chat-source user]}]
          {:capture (commands/execute! "temps"
                      {:chat-source chat-source :user user :yetibot-user {:id "BOT"}
                       :actor {:id (:id user)} :toolset :search
                       :before-expression! (fn [& _])})
           :calls @calls :sent @sent})))))

(fact "actual temps alias captures five complete aligned raw and reviewed records, once each"
  (let [{:keys [capture calls sent]} (capture-temps nil)
        pipeline (:pipeline capture)
        reviewed (public/projection (:public-result pipeline))]
    (:status capture) => :success
    (:capture-available? capture) => true
    (mapv #(first (string/split % #"°F")) (:value pipeline))
    => ["30" "37" "50" "55" "64"]
    (:data pipeline) => (mapv rows [0 1 3 4 2])
    (:data-collection pipeline) => (mapv rows [0 1 3 4 2])
    (mapv #(get-in % [:current :temp]) (:result/public-data reviewed))
    => [-1.0 3.0 10.0 13.0 18.0]
    (every? #(= "C" (get-in % [:units :temperature])) (:result/public-data reviewed)) => true
    (string/includes? (pr-str reviewed) "PRIVATE") => false
    (frequencies calls) => (zipmap locations (repeat 1))
    sent => []
    (count (:expressions capture)) => 8
    (:parent-ordinal (first (:expressions capture))) => nil
    (every? :parent-ordinal (rest (:expressions capture))) => true))

(fact "a failed nested location remains an error receipt, not an invented observation"
  (let [{:keys [capture calls sent]} (capture-temps "nyc")
        expressions (:expressions capture)
        failures (filter #(= :error (:status %)) expressions)
        data (remove nil? (get-in capture [:pipeline :data]))]
    (frequencies calls) => (zipmap locations (repeat 1))
    (count data) => 4
    (set (map :city_name data)) => #{"edmonton" "59106" "sunnyvale" "48178"}
    (count failures) => 1
    (string/includes? (unparser/unparse (:tree (first failures))) "nyc") => true
    sent => []))

(def controls (atom 0))
(defn control-fixture [args]
  (execution/require-direct! args)
  (swap! controls inc)
  "changed")

(fact "generated control text cannot become the adapter's original direct request"
  (reset! controls 0)
  (with-redefs [hooks/hooks (atom @hooks/hooks)
                hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                channel/settings-for-chat-source (constantly {})]
    (hooks/cmd-hook #"ai" #"limits reset" control-fixture)
    (support/in-event support/request "ai chart temps"
      (fn [{:keys [chat-source user]}]
        (let [result (commands/execute! "ai limits reset"
                       {:chat-source chat-source :user user :yetibot-user {:id "BOT"}
                        :actor {:id (:id user)} :toolset :search
                        :before-expression! (fn [& _])})]
          (:status result) => :unknown
          @controls => 0)))))
