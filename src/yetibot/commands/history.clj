(ns yetibot.commands.history
  (:require [yetibot.models.history :as h])
  (:use [yetibot.util :only (cmd-hook)]))

(defn history-cmd
  "history # show the last 10 items from chat history"
  []
  (take-last 10 (h/fmt-items-with-user)))

(cmd-hook #"history"
          _ (history-cmd))
