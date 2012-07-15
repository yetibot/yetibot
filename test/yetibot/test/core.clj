(ns yetibot.test.core
  (:use [yetibot.core])
  (:use [clojure.test]
        [clojure.tools.logging]
        [clj-logging-config.log4j]))

(set-logger!)

(trace "trace")
(warn "warn")
(info "info")

;(deftest replace-me ;; FIXME: write
;  (is false "No tests have been written."))
