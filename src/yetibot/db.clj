(ns yetibot.db
  (:require
    [yetibot.config :as config :refer [config-for-ns conf-valid?]]
    [datomico.db :as db]
    [datomico.core :as dc]
    [datomic.api :as api]
    [taoensso.timbre :refer [info warn error]]))

; TODO: loop through namespaces looking for a "schema" to load
; (do it manually for now)
(def nss '[yetibot.models.log
           yetibot.models.twitter
           yetibot.models.history
           yetibot.models.status
           yetibot.models.alias])

(def schemas
  (for [n nss] (do (require n) (deref (ns-resolve n 'schema)))))

(defn start []
  (if (conf-valid?)
    (do
      (info "☐ Loading Datomic schemas")
      (dc/start {:uri (:datomic-url (config-for-ns))
                 :schemas schemas})
      (info "☑ Datomic connected"))
    (warn ":datomic-url is not configured, unable to connect.")))
