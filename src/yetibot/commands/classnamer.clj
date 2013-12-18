(ns yetibot.commands.classnamer
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [fetch]]))

(def endpoint "http://www.classnamer.com/index.txt")

(defn classnamer-cmd
  "classnamer # retrieves a legit OO class name"
  [_] (s/trim (fetch endpoint)))

(cmd-hook #"classnamer"
          _ classnamer-cmd)
