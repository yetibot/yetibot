(ns yetibot.db
  (:require
    [yetibot.config :refer [get-config config-for-ns conf-valid?]]
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
  (if (conf-valid? (get-config :yetibot :db))
    (do
      (info "☐ Loading Datomic schemas")
      (dc/start {:uri (:datomic-url (get-config :yetibot :db))
                 :schemas schemas})
      (info "☑ Datomic connected"))
    (warn ":datomic-url is not configured, unable to connect.")))


(defn repl-start []
  (if (conf-valid? (get-config :yetibot :db))
    (do
      (info "☐ Loading Datomic schemas with dynamic-vars for repl")
      (dc/start {:uri (:datomic-url (get-config :yetibot :db))
                 :dynamic-vars true
                 :schemas schemas})
      (info "☑ Datomic connected"))
    (warn ":datomic-url is not configured, unable to connect.")))
