(ns yetibot.observers.ai-mcp-catalog
  "Register lifecycle hooks only; requiring this namespace starts no workers."
  (:require [robert.hooke :as hooke]
            [yetibot.core.adapters :as adapters]
            [yetibot.core.loader :as loader]
            [yetibot.models.ai-mcp-catalog :as catalog]
            [yetibot.models.ai-provider-catalog :as providers]))

(defn after-commands [f & args]
  (let [result (apply f args)]
    (catalog/start!)
    (providers/start!)
    result))

(defn before-stop [f & args]
  (providers/stop!)
  (catalog/stop!)
  (apply f args))

;; Pinned core's -main invokes load-all -> load-observers -> load-commands.
;; Observers are loaded before commands, so startup happens on the same boot,
;; not on the first chat event. Hook registration is idempotent on reload.
(hooke/add-hook #'loader/load-commands #'after-commands)
(hooke/add-hook #'adapters/stop #'before-stop)
