(ns yetibot.commands.clojure
  (:require [clojure.string :as s])
  (:use [yetibot.util]))

(def endpoint "http://tryclj.com/eval.json")

(defn try-clojure [expr]
  (let [uri (str endpoint "?" (map-to-query-string {:expr expr}))]
    (get-json uri)))

(defn clojure-cmd
  "clojure <expression> # evaluate a clojure expression"
  [expr]
  (:result (try-clojure expr)))

(cmd-hook #"clojure"
          #".*" (clojure-cmd p))
