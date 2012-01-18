(ns yetibot.help
  (:require [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [clojure.data.json :as json]))

(def docs (atom {}))

(defn add-docs [prefix cmds]
  (println (str "adding docs for " prefix (str cmds)))
  ; add to the docs atom using prefix string as the key
  (swap! docs conj {(str prefix) (remove nil? cmds)}))

(defn get-docs []
  @docs)

(defn get-docs-for [prefix]
  (get (get-docs) prefix))
