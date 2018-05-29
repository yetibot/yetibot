(ns yetibot.commands.classnamer
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [fetch]]))

(def endpoint "https://www.classnamer.org/type/plain")

(defn classnamer-cmd
  "classnamer # retrieves a legit OO class name"
  [_] (s/trim (fetch endpoint)))

(cmd-hook #"classnamer"
          _ classnamer-cmd)
