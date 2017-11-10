(ns yetibot.commands.clojure
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [get-json map-to-query-string]]))

(def endpoint "http://www.tryclj.com/eval.json")

(defn try-clojure [expr]
  (let [uri (str endpoint "?" (map-to-query-string {:expr expr}))]
    (get-json uri)))

(defn clojure-cmd
  "clj <expression> # evaluate a clojure expression"
  {:yb/cat #{:broken}}
  [{:keys [args]}]
  "tryclj.com is down :(")

  ;; (let [json (try-clojure args)]
  ;;   (if (:error json)
  ;;     (:message json)
  ;;     (:result json))))

(cmd-hook #"clj"
          #"\S*" clojure-cmd)
