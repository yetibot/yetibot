(ns yetibot.util
  (:require
    [datomico.db :as db :refer [with-latest-database]]
    [datomic.api :as api]
    [http.async.client :as client]
    [clojure.string :as s]
    [robert.hooke :as rh]
    [clojure.stacktrace :as st]
    [clojure.data.json :as json])
  (:use [clojure.contrib.cond]))


(defmacro with-fresh-db
  [& body]
  `(when (bound? #'db/*uri*)
     (binding [db/*connection* (api/connect db/*uri*)]
       (with-latest-database
         ~@body))))

(def env
  (let [e (into {} (System/getenv))]
    (zipmap (map keyword (keys e)) (vals e))))

(defn make-config
  ^{:deprecated "0.1.0"}
  [required-keys] (into {} (map (fn [k] [k (env k)]) required-keys)))

(defn conf-valid?
  ^{:deprecated "0.1.0"}
  [c] (every? identity (vals c)))

(defmacro ensure-config
  ^{:deprecated "0.1.0"}
  [& body]
  `(when (every? identity ~'config)
     ~@body))

(defn psuedo-format
  "Similar to clojure.core/format, except it only supports %s, and it will replace
   all occurances of %s with the single arg. If there is no %s found, it appends the
   arg to the end of the string instead."
  [s arg]
  (if (re-find #"\%s" s)
    (s/replace s "%s" arg)
    (str s " " arg)))

;;; collection parsing

; helpers for all collection cmds
(defn ensure-items-collection [items]
  {:pre [(not (nil? items))]}
  (if (coll? items)
    items
    (s/split items #"\n")))

; keys / vals helpers
(defn map-like? [items]
  (or (map? items)
      (every? #(re-find #".+:.+" %) items)))

(defn split-kvs
  "split into a nested list [[k v]] instead of a map so as to maintain the order"
  [items]
  (if (map-like? items)
    (if (map? items)
      (map vector (keys items) (vals items))
      (map #(s/split % #":") items))))

(defn split-kvs-with [f items]
  "accepts a function to map over the split keys from `split-kvs`"
  (if-let [kvs (split-kvs items)]
    (map (comp s/trim f) kvs)
    items))

