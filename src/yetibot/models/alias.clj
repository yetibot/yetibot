(ns yetibot.models.alias
  (:require [datomico.core :as dc]
            [datomico.db :refer [q]]
            [datomico.action :refer [all where raw-where]]))

(def model-ns :alias)

(def schema (dc/build-schema model-ns
                             [[:userid :string] ; user-id was a long and can't be changed
                              [:alias-cmd :string]]))

(dc/create-model-fns model-ns)
