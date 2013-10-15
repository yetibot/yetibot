(ns yetibot.commands.history
  (:require
    [yetibot.models.history :as h]
    [yetibot.hooks :refer [cmd-hook]]))

(defn history-cmd
  "history # show chat history"
  [{:keys [chat-source]}] (h/fmt-items-with-user chat-source))

(cmd-hook #"history"
          _ history-cmd)
