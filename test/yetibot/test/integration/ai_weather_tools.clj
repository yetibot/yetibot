(ns yetibot.test.integration.ai-weather-tools
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.weather :as weather]
            [yetibot.models.ai-native-tools :as native]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-weather-tools :as capability]
            [yetibot.test.support.ai-execution :as execution]))

(def toolset {:name "weather-test" :kind "search" :servers []
              :native-tools ["weather"] :users ["U1"] :adapters ["slack"]
              :providers [{:name "fake" :models ["fake"]}]})
(def config {:toolset toolset})
(def current {:city_name "Seattle" :state_code "WA" :country_code "US"
              :temp 21.0 :app_temp 22.0 :wind_spd 5 :wind_cdir "SW" :rh 60
              :weather {:description "Clear" :private "PRIVATE-NESTED"}
              :headers {:authorization "PRIVATE-KEY"} :private "PRIVATE-RAW"})

(defn call [args & [cfg]]
  (execution/in-event execution/request "grok run weather and suggest an alias"
    (fn [request]
      (capability/call! (or cfg config) "weather" args
                        (:chat-source request)))))

(fact "weather is a read-only structured native capability"
  (get-in (native/definition "weather") [:inputSchema :type]) => "object"
  (get-in (native/definition "weather") [:annotations :readOnlyHint]) => true)

(fact "real weather commands expose only reviewed normalized fields"
  (let [reads (atom [])]
    (with-redefs [weather/default-zip "Default City"
                  weather/current (fn [location]
                                    (swap! reads conj location)
                                    {:data [current]})]
      (doseq [[args location] [[{} "Default City"]
                               [{"location" "Seattle" "units" "F"}
                                "Seattle"]]]
        (let [reply (call args)]
          (get-in reply [:structuredContent :current :temp]) => 21.0
          (get-in reply [:structuredContent :units :temperature]) => "C"
          (get-in reply [:structuredContent :units :wind]) => "km/h"
          (get-in reply [:structuredContent :untrusted]) => true
          (str/includes? (pr-str reply) "PRIVATE-") => false
          (last @reads) => location))
      (count @reads) => 2)))

(fact "invalid arguments, untrusted calls and policy denials do no lookup"
  (with-redefs [weather/current (fn [& _]
                                (throw (AssertionError. "Unexpected HTTP")))]
    (doseq [args [{"location" ""} {"location" "-f Seattle"}
                  {"location" "Seattle\nother"} {"units" "Kelvin"}
                  {"command" "weather | cron stop"} {"location" (apply str (repeat 201 "a"))}]]
      (fact (:isError (call args)) => true))
    (:isError (capability/call! config "weather" {} {})) => true
    (:isError (call {} {:toolset (assoc toolset :users ["other"])})) => true))

(fact "transport failures are honest generic errors, not old readings"
  (doseq [lookup [(constantly {:error "PRIVATE-UPSTREAM"})
                  (fn [& _] (throw (Exception. "PRIVATE-ENDPOINT")))]]
    (with-redefs [weather/current lookup weather/default-zip "Seattle"]
      (let [reply (call {})]
        (:isError reply) => true
        (get-in reply [:structuredContent :current]) => nil
        (str/includes? (pr-str reply) "PRIVATE-") => false))))

(fact "the model receives a fresh tool-role snapshot to build its suggestion"
  (let [reads (atom 0) sent (atom [])]
    (with-redefs [weather/default-zip "Seattle"
                  weather/current (fn [_] (swap! reads inc) {:data [current]})
                  mcp/config (constantly {:servers [] :toolsets [toolset]})
                  ai/chat
                  (fn [_ _ messages _]
                    (swap! sent conj messages)
                    {:choices
                     [{:message
                       (if (= 1 (count @sent))
                         {:role "assistant"
                          :tool_calls [{:id "weather-1" :type "function"
                                        :function
                                        {:name "yetibot_weather"
                                         :arguments "{}"}}]}
                         {:role "assistant"
                          :content "Suggested: weather $s | render {{temp}}°C {{city_name}}"})}]})]
      (execution/in-event execution/request "grok run weather and suggest an alias"
        (fn [request]
          (:result/value
            (tools/complete {:name "fake"} "fake"
                            [{:role "user"
                              :content "Run weather, inspect data, suggest a one-line alias."}]
                            {:actor (policy/actor request)}))
          => "Suggested: weather $s | render {{temp}}°C {{city_name}}"))
      @reads => 1
      (let [message (first (filter #(= "tool" (:role %)) (last @sent)))
            reply (json/parse-string (:content message) true)]
        (:tool_call_id message) => "weather-1"
        (get-in reply [:structuredContent :current :city_name]) => "Seattle"
        (get-in reply [:structuredContent :current :temp]) => 21.0
        (str/includes? (json/generate-string @sent) "PRIVATE-") => false))))
