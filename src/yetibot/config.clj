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
  (let [new-conf (load-edn config-path)]
    (info "☐ Loading config")
    (reset! config new-conf)
    (info "☑ Config loaded")
    new-conf))

(defn get-config
  [& path]
  (let [path (if (coll? path) path [path])]
    (get-in @config path)))

(defn config-for-ns []
  (apply get-config (map keyword (split (str *ns*) #"\."))))

(defn conf-valid?
  ([] (conf-valid? (config-for-ns)))
  ([c]
   (and c
        (every? (complement (comp blank? str)) (vals c)))))

(defonce load-conf (reload-config))
