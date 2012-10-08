(ns yetibot.models.help
  (:require [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [clojure.contrib.string :as s]
            [clojure.data.json :as json]))

(def docs (atom {}))

(defn add-docs [prefix cmds]
  (println (str "adding docs for " prefix \newline (apply str cmds)))
  ; add to the docs atom using prefix string as the key
  (swap! docs conj {(str prefix) (set (map s/trim (remove nil? cmds)))}))

(defn get-docs []
  @docs)

(defn get-docs-for [prefix]
  (get (get-docs) prefix))
