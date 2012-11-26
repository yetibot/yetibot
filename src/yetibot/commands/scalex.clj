(ns yetibot.commands.scalex
  (:require [clojure.string :as s])
  (:use [yetibot.hooks :only [cmd-hook]]
        [yetibot.util.http :only [get-json]]))

(def endpoint "http://api.scalex.org/?per_page=1&q=")

(defn- format-docs [res]
  ((juxt :qualifiedName :signature (comp :source :comment) :docUrl) res))

(defn scalex-cmd
  "scalex <query> # searches scalex.org"
  [{query :args}]
  (let [res (get-json (str endpoint query))]
    (if-let [first-result (first (:results res))]
      (format-docs first-result)
      (format "No results for %s on scalex.org" query))))

(cmd-hook #"scalex"
          _ scalex-cmd)
