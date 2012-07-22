(ns yetibot.commands.classnamer
  (:use [yetibot.util :only [cmd-hook fetch]]))

(def endpoint "http://www.classnamer.com/index.txt")

(defn classnamer-cmd
  "classnamer # retrieves a legit OO class name"
  [] (fetch endpoint))

(cmd-hook #"classnamer"
          #".*" (classnamer-cmd))
