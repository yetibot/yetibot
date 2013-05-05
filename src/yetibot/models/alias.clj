(ns yetibot.models.alias
  (:require [datomico.core :as dc]
            [datomico.db :refer [q]]
            [datomico.action :refer [all where raw-where]]))

(def model-ns :alias)

; (def schema (dc/build-schema model-ns

