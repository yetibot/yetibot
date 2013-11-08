(ns yetibot.commands.clojure
  (:require [clojure.string :as s])
  (:use [yetibot.hooks :only [cmd-hook]]
        [yetibot.util.http :only [get-json map-to-query-string]]))

(def endpoint "http://tryclj.com/eval.json")

(defn try-clojure [expr]
  (let [uri (str endpoint "?" (map-to-query-string {:expr expr}))]
    (get-json uri)))

(defn clojure-cmd
  "clj <expression> # evaluate a clojure expression"
  [{:keys [args]}]
  (let [json (try-clojure args)]
    (if (:error json)
      (:message json)
      (:result json))))

(cmd-hook #"clj"
          #"\S*" clojure-cmd)
