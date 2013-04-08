(ns yetibot.observers.linter
  (:require
    [yetibot.api.github :as gh]
    [clojure.string :refer [split-lines]]
    [yetibot.campfire :refer [chat-data-structure]]
    [yetibot.hooks :refer [obs-hook]]))

(def addition? (partial re-find #"^\+"))
(def deletion? (partial re-find #"^\-"))
; TODO: could be improved by doing a single iteration with group-by
(defn adds-and-dels [lines transform]
  (map #(transform (filter % lines)) [addition? deletion?]))

;;; tabs

(defn count-tabs [lines]
 (reduce + (map (comp count (partial re-seq #"\t")) lines)))

(defn lint-tabs [patches]
  (reduce
    (fn [acc patch]
      (let [lines (split-lines patch)
            [tabs-added tabs-deleted] (adds-and-dels lines count-tabs)]
        {:added (+ (:added acc 0) tabs-added)
         :deleted (+ (:deleted acc 0) tabs-deleted)}))
    {} patches))

(def linters [lint-tabs])

(defn lint [repo commit]
  (let [patches (gh/patches repo commit)]
    (map #(% patches) linters)))

; (defn report []
;   (chat-data-structure
;     (yetibot.core/parse-and-handle-command
;       "image force push gif" nil nil)))

; (obs-hook
;   ["TextMessage" "PasteMessage"]
;   (fn [event-json]
;     (if-let [m (re-find regex (:body event-json))]
;       (report))))
