(ns user
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.config :as config]))

; can't override dev config because both dev and test profiles are loaded when
; you `lein run` or `lein repl` as it's common to run tests during dev. For
; travis, we need to instead include a `ci` profile that loads a custom config
; for tests to pass.

; (prn "test user")
; (info "Set testing config")
; (alter-var-root
;   #'config/config-path (fn [_] "test/config/config.edn"))
; (config/reload-config)
