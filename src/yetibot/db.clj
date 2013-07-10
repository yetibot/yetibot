(ns yetibot.db
  (:require [datomico.core :as dc]
            [yetibot.util :refer [env]]))

; todo: loop through namespaces looking for a "schema" to load
; (do it manually for now)
(def nss '[yetibot.models.history
           yetibot.models.status])

(def schemas
  (for [n nss] (do (require n)
                   (deref (ns-resolve n 'schema)))))

(def d (dc/start {:uri (:YETIBOT_DATOMIC_URL env)
                  :dynamic-vars true
                  :schemas schemas}))

(println "Datomic connected")

; (def dm (dc/start {:dynamic-vars true
;            :schemas [models.user/schema]}))
