(ns yetibot.test.config.load-sample-config
  (:require
    [clojure.test :refer :all]
    [clojure.edn :as edn]))

(deftest load-config-sample
  (is
    (edn/read-string (slurp "config/config-sample.edn"))
    "Should be able to load the sample config and parse it as EDN"))
