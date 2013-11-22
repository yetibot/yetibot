(ns yetibot.models.help
  (:require [clojure.string :as s]
            [clojure.data.json :as json]))

(defonce docs (atom {}))

(defn add-docs [prefix cmds]
  ; add to the docs atom using prefix string as the key
  (let [cmds (->> cmds
                  (remove nil?)
                  (map (comp
                         (partial s/join \newline)
                         (partial map s/trim)
                         s/split-lines)))]
    (swap! docs conj
           {(str prefix) (set cmds)})))


(defn get-docs []
  @docs)

(defn remove-docs [prefix]
  (swap! docs dissoc prefix))

(defn get-docs-for [prefix]
  (get (get-docs) prefix))
