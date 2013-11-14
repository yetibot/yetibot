(ns user
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.config :as config]))

(info "Set testing config")
(alter-var-root
  #'config/config-path (fn [_] "test/config/config.edn"))
(config/reload-config)
