(ns yetibot.config
  (:require [clojure.edn :as edn]))

(def ^:private config-path "config/config.edn")

(defonce ^:private config (atom nil))

(defn- load-edn [path]
  (edn/read-string (slurp path)))

(defn reload-config []
  (reset! config (load-edn config-path)))

(defn get-config
  [path]
  (let [path (if (coll? path) path [path])]
    (get-in @config (into [:yetibot] path))))

(defn start []
  (reload-config))
