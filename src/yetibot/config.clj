(ns yetibot.config
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.edn :as edn]
    [clojure.string :refer [blank?]]))

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
    (get-in @config (into [:yetibot] path))))

(defn conf-valid? [c]
  (every? (complement (comp blank? str)) (vals c)))

(defn start []
  (reload-config))
