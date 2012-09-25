(ns yetibot.commands.classnamer
  (:require
    [clojure.string :as s])
  (:use [yetibot.util :only [cmd-hook]]
        [yetibot.util.http :only [fetch]]))

(def endpoint "http://www.classnamer.com/index.txt")

(defn classnamer-cmd
  "classnamer # retrieves a legit OO class name"
  [] (s/trim (fetch endpoint)))

(cmd-hook #"classnamer"
          #".*" (classnamer-cmd))
