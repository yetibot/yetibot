(ns yetibot.commands.catchpoint
  (:require
    [taoensso.timbre :refer [info color-str]]
    [yetibot.api.catchpoint :as api]
    [clojure.string :as s]
    [clojure.core.strint :refer [<<]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn tests-cmd
  "catchpoint tests # retrieve the list of all tests from Catchpoint"
  [_]
  (api/tests))

(defn matched-tests-cmd
  "catchpoint tests <pattern> # retrieve tests with name matching <pattern>"
  [{[_ test-pattern] :match}]
  (api/resolve-tests test-pattern)
  )

(defn tests-show-cmd
  "catchpoint tests show <test-name-or-id> # show details for test by name or ID"
  [{[_ test-id-or-match] :match}]
  (if-let [test-id (api/resolve-test-id test-id-or-match)]
    (let [test-data (-> test-id api/test-by-id :body)]
      {:result/data test-data
       :result/value (api/format-test-data test-data)})
    {:result/error (<< "Couldn't find a test matching ~{test-id-or-match}")}))

(def default-metric "17 is Webpage Response Time (ms)" 17)

(defn performance-cmd
  "catchpoint performance <test-name-or-id> [<metric-index-or-pattern>]
   # show performance averages metrics for test by name or ID for a given
   # metric pattern or index, defaulting to 17: Webpage Response (ms)"
  [{[_ test-id-or-match metric-index-or-pattern] :match}]
  (info (color-str :blue "performance-cmd") metric-index-or-pattern)
  (if-let [test-id (api/resolve-test-id test-id-or-match)]
    (let [{start-date :start
           end-date :end
           :as perf-data} (-> test-id api/raw-performance :body)

          metrics (->> perf-data :detail :fields :synthetic_metrics)

          {metric-index :index
           metric-name :name} (api/metric-by-index-or-pattern
                                (or metric-index-or-pattern default-metric)
                                metrics)

          items (-> perf-data :detail :items)
          start-and-end (<< "*Start*: ~{start-date} *End*: ~{end-date}")
          metrics (map
                    (fn [{{breakdown1-name :name} :breakdown_1
                          {breakdown2-name :name} :breakdown_2
                          metrics :synthetic_metrics}]
                      (<< "~{breakdown1-name} - *~{breakdown2-name}*: "
                          "~{(nth metrics metric-index)} "
                          "~{metric-name}"))
                    items)]
      {:result/data perf-data
       :result/value (conj metrics start-and-end)})
    {:result/error (<< "Couldn't find a test matching ~{test-id-or-match}")}))

(defn performance-metrics-cmd
  "catchpoint performance metrics <test-name-or-id>  # list available performance metrics for a test"
  [{[_ test-id-or-match] :match}]
  (if-let [test-id (api/resolve-test-id test-id-or-match)]
    (let [perf-data (-> test-id api/raw-performance :body)]
      {:result/data perf-data
       :result/value (->> perf-data :detail :fields :synthetic_metrics
                          (map (fn [{metric-name :name
                                     metric-index :index}]
                                 (<< "~{metric-index}: ~{metric-name}"))))})
    {:result/error (<< "Couldn't find a test matching ~{test-id-or-match}")}))

(cmd-hook #"catchpoint"
  #"tests\s+show\s+(\S+)" tests-show-cmd
  #"tests\s+(\S+)" matched-tests-cmd
  #"tests$" tests-cmd
  #"performance\s+metrics\s+(.+)" performance-metrics-cmd
  #"performance\s+(\S+)\s?(\S+)?" performance-cmd)
