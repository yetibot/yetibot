(ns yetibot.commands.nil
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn nil-cmd
  "nil # eats all passed args; equivalent to writing to /dev/null"
  {:yb/cat #{:util}}
  [_]
  "")

(cmd-hook #"nil"
          _ nil-cmd)
