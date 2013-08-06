(ns yetibot.commands.reload
  (:require [clojure.string :as s]
            [yetibot.hooks :refer [cmd-hook]]))

(defn reload-all-cmd
  "reload # reload all of YetiBot's commands and observers"
  [_]
  (yetibot.loader/reload-all-yetibot)
  "Reload complete.")

(defn reload-cmd
  "reload <namespace pattern> # reload a specific namespace"
  [{:keys [match]}]
  (let [re (re-pattern (format ".*%s.*" match))
        matched (yetibot.loader/find-and-load-namespaces [re])]
    (format "Reloaded namespaces:\n  %s" (s/join "\n  " matched))))

(cmd-hook #"reload"
          #".+" reload-cmd
          _ reload-all-cmd)
