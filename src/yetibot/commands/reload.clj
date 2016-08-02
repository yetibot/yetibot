(ns yetibot.commands.reload
  (:require
    [clojure.string :as s]
    [yetibot.core.loader :refer [find-and-load-namespaces]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn reload-all-cmd
  "reload # reload all of YetiBot's commands and observers"
  {:yb/cat #{:util}}
  [_]
  "No longer implemented")

(defn reload-cmd
  "reload <namespace pattern> # reload a specific namespace"
  {:yb/cat #{:util}}
  [{:keys [match]}]
  (let [re (re-pattern (format ".*%s.*" match))
        matched (find-and-load-namespaces [re])]
    (format "Reloaded namespaces:\n  %s" (s/join "\n  " matched))))

(cmd-hook #"reload"
  #".+" reload-cmd
  _ reload-all-cmd)
