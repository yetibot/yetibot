(ns yetibot.commands.history
  (:require [yetibot.models.history :as h])
  (:use [yetibot.hooks :only [cmd-hook]]))

(defn history-cmd
  "history # show chat history" 
  [_] (h/fmt-items-with-user))

(cmd-hook #"history"
          _ history-cmd)
