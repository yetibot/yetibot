(ns yetibot.commands.curl
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn nil-cmd
  "nil # eats all passed args; equivalent to writing to /dev/null"
  [_]
  "")

(cmd-hook #"nil"
          _ nil-cmd)
