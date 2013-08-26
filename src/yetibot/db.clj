(ns yetibot.db
  (:require [datomico.core :as dc]
            [yetibot.util :refer [env]]))

; TODO: loop through namespaces looking for a "schema" to load
; (do it manually for now)
(def nss '[yetibot.models.twitter
           yetibot.models.history
           yetibot.models.status
           yetibot.models.alias])

(def schemas
  (for [n nss] (do (require n)
                   (deref (ns-resolve n 'schema)))))

(def d (dc/start {:uri (:YETIBOT_DATOMIC_URL env)
                  :dynamic-vars true
                  :schemas schemas}))

(println "✓ Datomic connected")
