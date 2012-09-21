(ns yetibot.commands.scalex
  (:require [clojure.string :as s])
  (:use [yetibot.util :only (cmd-hook)]
        [yetibot.util.http :only (get-json fetch)]))

(def endpoint "http://api.scalex.org/?per_page=1&q=")

(defn- format-docs [res]
  ((juxt :qualifiedName :signature :docUrl) res))

(defn scalex-cmd
  "scalex <query> # searches scalex.org"
  [query]
  (let [res (get-json (str endpoint query))]
    (if-let [first-result (first (:results res))]
      (format-docs first-result)
      (str "No results for " query " on scalex.org"))))

(cmd-hook #"scalex"
          _ (scalex-cmd p))
