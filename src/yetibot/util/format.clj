(ns yetibot.util.format
  (:require [clojure.string :as s])
  (:import [clojure.lang Associative Sequential]))

(defmulti ^:private format-flattened type)

; send map as key: value pairs
(defmethod format-flattened Associative [d]
  (format-flattened (map (fn [[k v]] (str k ": " v)) d)))

(defmethod format-flattened Sequential [d]
  (s/join \newline d))

(prefer-method format-flattened Sequential Associative)

; default handling for strings and other non-collections
(defmethod format-flattened :default [d]
  (str d))

(defn format-data-structure
  "returns a string representation of `d` as well as the fully-flattened data representation"
  [d]
  (if (and (not (map? d))
           (coll? d)
           (coll? (first d)))
    ; if it's a nested sequence, recursively flatten it
    (if (map? (first d))
      ; merge if the insides are maps
      (format-data-structure (apply merge-with d))
      ; otherwise flatten
      (format-data-structure (apply concat d)))
    ; otherwise send in the most appropriate manner
    (let [ds (if (set? d) (seq d) d)]
      [(format-flattened ds) ds])))

(defn format-data-as-string [d]
  (let [[s _] (format-data-structure d)]
    s))
