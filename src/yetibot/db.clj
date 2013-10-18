(ns yetibot.db
  (:require
    [datomico.db :as db]
    [datomico.core :as dc]
    [datomic.api :as api]
    [taoensso.timbre :refer [info warn error]]
    [yetibot.util :refer [env]]))

; TODO: loop through namespaces looking for a "schema" to load
; (do it manually for now)
(def nss '[yetibot.models.log
           yetibot.models.twitter
           yetibot.models.history
           yetibot.models.status
           yetibot.models.alias])

(def schemas
  (for [n nss] (do (require n) (deref (ns-resolve n 'schema)))))

(info "Loading Datomic schemas")

(defn start []
  (dc/start {:uri (:YETIBOT_DATOMIC_URL env)
             :schemas schemas}))

(info "✓ Datomic connected")
