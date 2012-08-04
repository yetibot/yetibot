(ns yetibot.commands.clojure
  (:require [clojure.string :as s])
  (:use [yetibot.util]
        [yetibot.util.http]))

(def endpoint "http://tryclj.com/eval.json")

(defn try-clojure [expr]
  (let [uri (str endpoint "?" (map-to-query-string {:expr expr}))]
    (get-json uri)))

(defn clojure-cmd
  "clojure <expression> # evaluate a clojure expression"
  [expr]
  (let [json (try-clojure expr)]
    (if (:error json)
      (:message json)
      (:result json))))

(cmd-hook #"clojure"
          #".*" (clojure-cmd p))
