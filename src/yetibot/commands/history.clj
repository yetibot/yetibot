(ns yetibot.commands.history
  (:require [yetibot.models.history :as h])
  (:use [yetibot.util :only (cmd-hook)]))

(defn history-cmd
  "history # show chat history" 
  []
  (h/fmt-items-with-user))

(cmd-hook #"history"
          _ (history-cmd))
