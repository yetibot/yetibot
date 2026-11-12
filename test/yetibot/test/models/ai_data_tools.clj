(ns yetibot.test.models.ai-data-tools
  (:require [cheshire.core :as json]
            [midje.sweet :refer :all]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-charts :as charts]
            [yetibot.models.ai-data-tools :as tools]))

(fact "generic command dates become temporal fields, decimal-looking strings stay nominal"
  (let [e (tools/envelope
            {:data-collection [{:date "2026-11-01" :amount 1 :identifier "100.00"}
                               {:date "2026-11-02" :amount 2 :identifier "200.00"}]} [])
        fields (into {} (map (juxt :name :type) (:fields e)))
        compiled (charts/compile-chart e {"kind" "line" "x" "date" "y" "amount"})]
    fields => {"date" :temporal "amount" :quantitative "identifier" :nominal}
    (get-in e [:coverage :status]) => :unknown
    (:ok? compiled) => true
    (count (get-in (json/parse-string (get-in compiled [:request :source]) true) [:data :values])) => 2))

(fact "missing generic observations remain chart gaps, not a silently shortened dataset"
  (let [e (tools/envelope {:data-collection [{:date "2026-11-01" :value 1} nil
                                            {:date "2026-11-03" :value 3}]} [])
        compiled (charts/compile-chart e {"kind" "line" "x" "date" "y" "value"})]
    (count (:rows e)) => 3
    (second (:rows e)) => {}
    (get-in e [:coverage :status]) => :partial
    (:ok? compiled) => true
    (get-in (json/parse-string (get-in compiled [:request :source]) true) [:data :values 1 :y]) => nil))

(fact "JSON result keys cannot grant weather units or completeness"
  (let [projection {:result/public-data [{:source "wttr.in" :units {:temperature "C"}
                                        :current {:temp 3}}]}
        pipeline {:data-collection [{:temp 3}] :public-result projection}]
    (:source (tools/envelope pipeline [])) => "yetibot-command"
    (get-in (tools/envelope pipeline []) [:fields 0 :unit]) => nil
    (:source (tools/envelope (assoc pipeline :public-result (public/reviewed projection)) [])) => "weather"))

(fact "normalized-key collisions cannot silently replace an observation"
  (tools/envelope {:data-collection [{:value 1 "value" 2}]} []) => (throws Exception))
