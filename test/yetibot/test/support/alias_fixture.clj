(ns yetibot.test.support.alias-fixture
  "Load the real alias command without its background deployment-store read."
  (:require [yetibot.core.db.alias :as alias-db]))

;; Wait only for a loader this fixture starts. Coverage may already have loaded
;; core commands; its existing loader and failure belong to that earlier load.
(when-not (find-ns 'yetibot.core.commands.alias)
  (with-redefs [alias-db/find-all (constantly [])]
    (require 'yetibot.core.commands.alias)
    (let [loader (var-get
                   (ns-resolve 'yetibot.core.commands.alias 'loader))]
      (when (= ::pending (deref loader 5000 ::pending))
        (future-cancel loader)
        (throw (ex-info "The in-memory alias fixture did not initialize." {}))))))
