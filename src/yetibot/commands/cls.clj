(ns yetibot.commands.cls
  (:require [yetibot.core.hooks :refer [cmd-hook]]))

(def cls-image "http://virtualeconomics.typepad.com/photos/uncategorized/ae1_3.jpg")

(defn clear-screen-cmd
  "cls # clear screen after your co-worker posts something inappropriate"
  [_] cls-image)

(cmd-hook ["cls" #"^cls$"]
          _ clear-screen-cmd)
