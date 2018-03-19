(ns yetibot.commands.clojure
  (:require
    [clojure.string :as s]
    [clojail.core :refer [sandbox]]
    [clojail.testers :refer [secure-tester]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [taoensso.timbre :refer [error debug info color-str]]
    [yetibot.core.util.http :refer [get-json map-to-query-string]]))

(def sb (sandbox secure-tester :timeout 5000))

(defn clojure-cmd
  "clj <expression> # evaluate a clojure expression"
  {:yb/cat #{:util}}
  [{:keys [args]}]
  (try
    (pr-str (sb (read-string args)))
    (catch Throwable e
      (info "Clojail erroed" e)
      (throw e) e)))

(cmd-hook #"clj"
          #"\S*" clojure-cmd)
