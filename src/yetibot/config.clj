(ns yetibot.config
  "Config is stored in an edn file. The config data structure maps to the
   namespaces of the code that depends on the config."
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.edn :as edn]
    [clojure.string :refer [blank? split]]))

(def ^:private config-path "config/config.edn")

(defonce ^:private config (atom nil))

(defn- load-edn [path]
  (edn/read-string (slurp path)))

(defn reload-config []
  (info "☐ Loading config")
  (reset! config (load-edn config-path))
  (info "☑ Config loaded"))

(defn get-config
  [path]
  (let [path (if (coll? path) path [path])]
    (get-in @config path)))

(defn config-for-ns []
  (get-config (map keyword (split (str *ns*) #"\."))))

(defn conf-valid?
  ([] (conf-valid? (config-for-ns)))
  ([c] (every? (complement (comp blank? str)) (vals c))))

(defn start []
  (reload-config))
