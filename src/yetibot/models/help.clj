(ns yetibot.models.help
  (:require [clojure.string :as s]
            [clojure.data.json :as json]))

(defonce docs (atom {}))

(defn add-docs [prefix cmds]
  ; add to the docs atom using prefix string as the key
  (swap! docs conj {(str prefix) (set (map s/trim (remove nil? cmds)))}))

(defn get-docs []
  @docs)

(defn get-docs-for [prefix]
  (get (get-docs) prefix))
