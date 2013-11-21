(ns yetibot.config
  "Config is stored in an edn file. The config data structure maps to the
   namespaces of the code that depends on the config."
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.pprint :refer [pprint]]
    [clojure.edn :as edn]
    [clojure.string :refer [blank? split]]))

(def config-path "config/config.edn")

(defonce ^:private config (atom nil))

(defn- load-edn [path]
  (try
    (edn/read-string (slurp path))
    (catch Exception _
      (warn "Config is missing")
      {:yetibot {}})))

(defn reload-config []
  (let [new-conf (load-edn config-path)]
    (info "☐ Loading config at" config-path)
    (reset! config new-conf)
    (info "☑ Config loaded")
    new-conf))

(defn get-config
  [& path]
  (let [path (if (coll? path) path [path])]
    (get-in @config path)))

(defn write-config []
  (spit config-path (with-out-str (pprint @config))))

(defn update-config
  "Update the config data structure and write it to disk."
  [& path-and-val]
  (let [path (butlast path-and-val)
        value (last path-and-val)]
    (swap! config update-in path (constantly value))
    (write-config)))

(defn config-for-ns []
  (apply get-config (map keyword (split (str *ns*) #"\."))))

(defn conf-valid?
  ([] (conf-valid? (config-for-ns)))
  ([c]
   (and c
        (every? (complement (comp blank? str)) (vals c)))))

(defonce load-conf (reload-config))
