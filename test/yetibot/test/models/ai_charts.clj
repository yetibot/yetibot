(ns yetibot.test.models.ai-charts
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.models.ai-charts :as charts]))

(def weather
  {:source "weather" :captured-at "2026-11-11T10:00:00Z"
   :coverage {:status :complete :requested 5 :succeeded 5 :failed 0}
   :fields [{:name "city" :path ["city"] :type :nominal}
            {:name "temperature" :path ["temp"] :type :quantitative :unit "C"}]
   :rows (mapv (fn [city temp] {"city" city "temp" temp})
               ["edmonton" "59106" "48178" "sunnyvale" "boston"]
               [-1 3 13 10 5])})
(def bar {"kind" "bar" "x" "city" "y" "temperature"})
(defn spec [result] (json/parse-string (get-in result [:request :source]) true))

(fact "Five locations compile without copying display strings or dropping rows"
  (let [r (charts/compile-chart weather bar) s (spec r)]
    (:ok? r) => true
    (select-keys (:request r) [:type :format]) => {:type "vegalite" :format "auto"}
    (get-in s [:mark :type]) => "bar"
    (mapv :x (get-in s [:data :values]))
    => ["edmonton" "59106" "48178" "sunnyvale" "boston"]
    (mapv :y (get-in s [:data :values])) => [-1 3 13 10 5]
    (get-in s [:encoding :y :title]) => "temperature (C)"
    (string/includes? (:caption r) "5 of 5") => true
    (:coverage r) => (:coverage weather)))

(def history
  {:source "wise" :captured-at "2026-11-11T10:00:00Z" :series-field "balance"
   :coverage {:status :complete :from "2026-11-01T00:00:00Z"
              :to "2026-11-11T10:00:00Z"}
   :fields [{:name "date" :path ["date"] :type :temporal}
            {:name "amount" :path ["amount"] :type :quantitative
             :unit-field "currency" :semantic :transaction-amount}
            {:name "running-balance" :path ["running"] :type :quantitative
             :unit-field "currency" :semantic :running-balance}
            {:name "balance" :path ["balance"] :type :nominal}
            {:name "currency" :path ["currency"] :type :nominal}]
   :rows [{"date" "2026-11-01T00:00:00Z" "amount" "-1.25"
           "running" "10.00" "currency" "USD" "balance" "USD balance 1"}
          {"date" "2026-11-02T00:00:00Z" "amount" "2.30"
           "running" "12.30" "currency" "USD" "balance" "USD balance 1"}
          {"date" "2026-11-01T00:00:00Z" "amount" "3.00"
           "running" "30.00" "currency" "EUR" "balance" "EUR balance 2"}]})
(def line {"kind" "line" "x" "date" "y" "running-balance"})

(fact "Authoritative running points preserve balance identity and separate currencies"
  (let [r (charts/compile-chart history (assoc line "title" "Made up daily spending"))
        s (spec r)]
    (:ok? r) => true
    (:title s) => "Statement running-balance points"
    (get-in s [:facet :field]) => "unit"
    (get-in s [:resolve :scale :y]) => "independent"
    (get-in s [:spec :encoding :color :field]) => "series"
    (get-in s [:data :values 0 :y]) => 10.00M
    (count (get-in s [:data :values])) => 3
    (string/includes? (:caption r) "not daily or opening balances") => true))

(fact "Partial generic time series retains null gaps and discloses coverage"
  (let [dataset (-> history
                    (assoc :source "sensor" :series-field nil
                           :coverage {:status :partial :requested 4 :succeeded 3 :failed 1})
                    (assoc :fields [{:name "date" :path ["date"] :type :temporal}
                                    {:name "value" :path ["value"] :type :quantitative :unit "C"}]
                           :rows [{"date" "2026-11-01" "value" 2}
                                  {"date" "2026-11-02" "value" nil}
                                  {"date" "2026-11-03" "value" 4}]))
        r (charts/compile-chart dataset {"kind" "line" "x" "date" "y" "value"})]
    (:ok? r) => true
    (mapv :y (get-in (spec r) [:data :values])) => [2 nil 4]
    (get-in (spec r) [:mark :invalid]) => "break-paths-show-domains"
    (string/includes? (:caption r) "Partial coverage: 3 of 4") => true
    (string/includes? (:caption r) "1 missing measurement") => true))

(fact "Failed compilation has safe authored diagnostics and never renders"
  (doseq [[dataset intent field code]
          [[weather (assoc bar "x" "PRIVATE") "x" :unavailable-field]
           [weather (assoc bar "kind" "flowchart") "kind" :enum]
           [weather (assoc bar "url" "PRIVATE") "*" :unknown-field]
           [weather (assoc bar "title" {}) "title" :type]
           [weather (dissoc bar "y") "y" :required]
           [(assoc weather :rows []) bar "dataset" :incomplete-scope]
           [weather (assoc bar "y" "city") "y" :type]
           [weather (assoc bar "kind" "line") "x" :type]
           [(assoc-in weather [:rows 0 "temp"] "1.0") bar "y" :type]
           [(assoc-in weather [:rows 0 "temp"] Double/NaN) bar "y" :type]
           [(assoc-in history [:rows 0 "date"] "PRIVATE DATE") line "x" :type]
           [(assoc-in history [:rows 0 "currency"] nil) line "y" :incompatible-unit]
           [(assoc-in history [:rows 0 "running"] nil) line "y" :incomplete-scope]
           [(assoc-in history [:coverage :status] :partial) line "y" :incomplete-scope]
           [(assoc-in weather [:fields 1 :unit] {}) bar "y" :incompatible-unit]
           [(update history :fields (fn [fs] (mapv #(dissoc % :unit-field) fs))) line "y" :incompatible-unit]
           [(assoc-in history [:rows 0 "currency"] "USD/EUR") line "y" :incompatible-unit]]]
    (let [calls (atom []) r (charts/compile-chart dataset intent)]
      (:ok? r) => false
      (select-keys (:problem r) [:stage :field :code :execution-began])
      => {:stage :chart :field field :code code :execution-began false}
      (string/includes? (str r) "PRIVATE") => false
      (charts/render-chart! #(swap! calls conj %) r) => r
      @calls => [])))

(fact "Snapshot money cannot be relabeled into a historical line"
  (let [dataset (assoc history :fields
                       (mapv #(if (= "running-balance" (:name %))
                                (assoc % :semantic :current-balance) %) (:fields history)))]
    (get-in (charts/compile-chart dataset line) [:problem :code]) => :incomplete-scope))

(fact "Literal labels cannot introduce executable Vega keys or external data"
  (let [label "datum.x; https://evil.invalid @everyone"
        dataset (-> weather
                    (assoc-in [:fields 0 :name] "a.b[0]")
                    (assoc-in [:rows 0 "city"] label))
        r (charts/compile-chart dataset (assoc bar "x" "a.b[0]"))
        s (spec r)]
    (:ok? r) => true
    (get-in s [:encoding :x :field]) => "x"
    (get-in s [:data :values 0 :x]) => label
    (contains? (:data s) :url) => false
    (contains? s :transform) => false
    (contains? s :params) => false))

(fact "Source bounds refuse whole data, not a sampled prefix"
  (let [dataset (assoc weather :rows
                       (mapv (fn [i] {"city" (str i (apply str (repeat 40 "界")))
                                      "temp" i}) (range 3000)))
        calls (atom 0) r (charts/compile-chart dataset bar)]
    (get-in r [:problem :code]) => :size
    (charts/render-chart! (fn [_] (swap! calls inc)) r) => r
    @calls => 0))

(fact "No silent bar overlap or automatic aggregation hides repeated inputs"
  (let [dataset (-> history
                    (assoc :rows [(assoc (first (:rows history)) "date" "2026-11-01T00:00:00Z" "category" "A")
                                  (assoc (first (:rows history)) "date" "2026-11-02T00:00:00Z" "category" "B"
                                         "balance" "USD balance 2")])
                    (update :fields conj {:name "category" :path ["category"] :type :nominal}))]
    (get-in (charts/compile-chart dataset {"kind" "bar" "x" "currency" "y" "amount" "series" "currency"})
            [:problem :code]) => :incomplete-scope
    (:ok? (charts/compile-chart dataset {"kind" "line" "x" "date" "y" "amount" "series" "category"})) => true
    (get-in (spec (charts/compile-chart dataset {"kind" "line" "x" "date" "y" "amount" "series" "category"}))
            [:encoding :detail :field]) => "hostSeries"))

(fact "Failed input labels and unknown upstream completeness remain explicit"
  (let [dataset (assoc weather :coverage {:status :partial :requested 6 :succeeded 5 :failed 1
                                         :failed-inputs ["missing-city"]})
        r (charts/compile-chart dataset bar)]
    (string/includes? (:caption r) "missing-city") => true
    (string/includes? (:caption (charts/compile-chart
                                 (assoc weather :coverage {:status :unknown}) bar)) "Coverage unknown") => true))

(fact "A quantitative x axis cannot combine different units either"
  (let [dataset {:source "sensor" :captured-at "2026-11-11T10:00:00Z"
                 :coverage {:status :complete}
                 :fields [{:name "distance" :path ["distance"] :type :quantitative :unit-field "unit"}
                          {:name "temperature" :path ["temperature"] :type :quantitative :unit "C"}
                          {:name "unit" :path ["unit"] :type :nominal}]
                 :rows [{"distance" 1 "unit" "km" "temperature" 10}
                        {"distance" 2 "unit" "mi" "temperature" 12}]}
        intent {"kind" "point" "x" "distance" "y" "temperature"}]
    (get-in (charts/compile-chart dataset intent) [:problem :field]) => "x"
    (get-in (charts/compile-chart dataset intent) [:problem :code]) => :incompatible-unit
    (get-in (spec (charts/compile-chart (assoc-in dataset [:rows 1 "unit"] "km") intent))
            [:encoding :x :title]) => "distance (km)"))

(fact "Renderer callback is called exactly once; its actual result is preserved"
  (let [compiled (charts/compile-chart weather bar) calls (atom [])
        actual {:result/error "Service unavailable"}
        r (charts/render-chart! #(do (swap! calls conj %) actual) compiled)]
    @calls => [(:request compiled)]
    r => {:result actual :caption (:caption compiled) :coverage (:coverage weather)}))

(fact "source-qualified decimal conversion is symmetric and diagnoses the selected axis"
  (let [dataset (-> history
                    (assoc :series-field nil :rows [(first (:rows history))])
                    (update :fields conj {:name "fees" :path ["fees"] :type :quantitative
                                          :unit-field "currency" :semantic :transaction-amount})
                    (assoc-in [:rows 0 "fees"] "0.10000001"))
        intent {"kind" "point" "x" "amount" "y" "fees"}]
    (:ok? (charts/compile-chart dataset intent)) => true
    (get-in (spec (charts/compile-chart dataset intent)) [:data :values 0 :x]) => -1.25
    (get-in (spec (charts/compile-chart dataset intent)) [:data :values 0 :y]) => 0.10000001
    (doseq [[d axis] [[(assoc dataset :source "generic") "x"]
                      [(assoc-in dataset [:rows 0 "amount"] "not numeric") "x"]
                      [(assoc-in dataset [:rows 0 "fees"] "not numeric") "y"]
                      [(update dataset :fields #(mapv (fn [f] (dissoc f :semantic)) %)) "x"]]]
      (select-keys (:problem (charts/compile-chart d intent)) [:field :code])
      => {:field axis :code :type})))
