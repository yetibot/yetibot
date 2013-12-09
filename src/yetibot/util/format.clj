(ns yetibot.util.format
  (:require
    [yetibot.util]
    [taoensso.timbre :refer [info warn error]]
    [clojure.stacktrace :as st]
    [clojure.string :as s])
  (:import [clojure.lang Associative Sequential]))

;; format alternates

(def general-subst-pattern #"%s")
(def num-subst-pattern #"%([0-9]+)")

(defn format-n
  "Replace numbered placeholders %1, %2..$n with the nth arg. All args don't
   have to be used."
  [s args]
  (if (re-find num-subst-pattern s)
    (let [n (->> s
                 (re-seq num-subst-pattern)
                 (map (comp read-string second))
                 seq
                 (apply max))]
      (reduce
        (fn [acc-to-fmt i]
          (s/replace acc-to-fmt (str "%" (inc i)) (str (nth args i ""))))
        s (range n)))
    s))

(defn pseudo-format
  "Similar to clojure.core/format, except it only supports %s, and it will
   replace all occurances of %s with the single arg. If there is no %s found, it
   appends the arg to the end of the string instead."
  [s arg]
  (if (re-find #"\%s" s)
    (s/replace s "%s" arg)
    s))

(defn pseudo-format-n
  "Combination of pseudo-format and format-n - it can do both if the string
   contains both %s and %1 substitution placeholders. If neither are found, it
   appends joined args to end of string."
  [s args]
  (let [gen? (re-find general-subst-pattern s)
        num? (re-find num-subst-pattern s)
        neither? (not (or gen? num?))
        joined (s/join " " args)]
    (cond-> s
      gen? (pseudo-format joined)
      num? (format-n args)
      neither? (str " " joined))))

;; chat formaters

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
  "Returns a tuple containing:
     - a string representation of `d`
     - the fully-flattened data representation"
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

(defn to-coll-if-contains-newlines
  "Convert a String to a List if the string contains newlines. Bit of a hack but it
   lets us get out of explicitly supporting streams in every command that we want
   it."
  [s]
  (if (and (string? s) (re-find #"\n" s))
    (s/split s #"\n")
    s))

(defn format-exception-log [ex]
  (with-out-str
    (newline)
    (st/print-stack-trace (st/root-cause ex) 50)))
