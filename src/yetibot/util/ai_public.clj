(ns yetibot.util.ai-public
  "Frozen JSON-compatible DTOs. Callers must first select trusted public fields."
  (:require [yetibot.core.util.public-result :as public]))

(defn- scalar-data [value]
  (cond
    (map? value) (into {} (map (fn [[k v]] [k (scalar-data v)])) value)
    (sequential? value) (mapv scalar-data value)
    (keyword? value) (name value)
    :else value))

(defn reviewed
  "Only for authored allowlists, never provider bodies or arbitrary tool data.
   Core's frozen viewer accepts JSON scalars, not keyword-valued enums."
  [result]
  (public/reviewed
    (cond-> result
      (contains? result :result/public-data)
      (update :result/public-data scalar-data)
      (contains? result :result/public-error)
      (update :result/public-error scalar-data))))
