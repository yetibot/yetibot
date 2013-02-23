(ns yetibot.db
  (:require [datomico.core :as dc]))

; todo: loop through namespaces looking for a "schema" to load
; (do it manually for now)
(def nss ['yetibot.models.history])

(def schemas
  (for [n nss] (do (require n)
                   (deref (ns-resolve n 'schema)))))

(def d (dc/start {:uri "datomic:free://localhost:4334/yetibot"
                  :dynamic-vars true
                  :schemas schemas}))

; (def dm (dc/start {:dynamic-vars true
;            :schemas [models.user/schema]}))
