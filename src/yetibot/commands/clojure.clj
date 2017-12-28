(ns yetibot.commands.clojure
  (:require
    [clojure.string :as s]
    [clojail.core :refer [sandbox]]
    [clojail.testers :refer [secure-tester]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [get-json map-to-query-string]]))

(def sb (sandbox secure-tester :timeout 5000))

(defn clojure-cmd
  "clj <expression> # evaluate a clojure expression"
  {:yb/cat #{:util}}
  [{:keys [args]}]
  (pr-str (sb (read-string args))))

(cmd-hook #"clj"
          #"\S*" clojure-cmd)
