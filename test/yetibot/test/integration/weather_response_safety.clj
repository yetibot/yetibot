(ns yetibot.test.integration.weather-response-safety
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [midje.sweet :refer [fact =>]]
            [yetibot.commands.weather :as weather]
            [yetibot.core.util.public-result :as public-result]))

(defn- diagnostic [result]
  (let [seen (atom nil)]
    (binding [public-result/*capture* #(reset! seen %)]
      (public-result/observe! result))
    (:public-error @seen)))

(fact "weather errors without an HTTP receipt still retain honest diagnostics"
  (doseq [response [nil {:error "No current conditions found."}]]
    (with-redefs [weather/current (constantly response)]
      (let [details (diagnostic (weather/weather-cmd {:match "San Francisco"}))]
        (:service details) => "wttr.in"
        (:stage details) => "weather response"
        (:kind details) => "unavailable"
        (:http-status details) => nil))))

(fact "an HTML 503 retains its status instead of failing automatic JSON coercion"
  (with-redefs [http/get
                (fn [_ options]
                  (if (= :json (:as options))
                    (throw (ex-info "PRIVATE-HTML-PARSE" {}))
                    {:status 503 :body "<html>PRIVATE-RESPONSE</html>"}))]
    (let [result (weather/weather-cmd {:match "Seattle"})]
      (:http-status (diagnostic result)) => 503
      (:kind (diagnostic result)) => "http"
      (str/includes? (pr-str result) "PRIVATE-") => false)))

(fact "malformed or oversized success bodies get a safe validation diagnostic"
  (doseq [body ["<html>PRIVATE-RESPONSE</html>"
               (apply str (repeat 524289 "x"))]]
    (with-redefs [http/get (fn [& _] {:status 200 :body body})]
      (let [result (weather/weather-cmd {:match "Seattle"})]
        (:http-status (diagnostic result)) => 200
        (:kind (diagnostic result)) => "invalid-response"
        (str/includes? (pr-str result) "PRIVATE-") => false))))
