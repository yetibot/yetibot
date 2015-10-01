(ns yetibot.commands.that
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn that-with-cmd-cmd
  "that cmd # retrieve last command from history"
  [{:keys [chat-source]}]
  ; the cmd used to call this will be in history, so get the last two then get
  ; the first for it
  (-> (h/last-chat-for-room chat-source true 2)
      h/touch-all
      first
      :history/body))

(defn that-cmd
  "that # retrieve last non-command chat from history"
  [{:keys [chat-source]}]
  (-> (h/last-chat-for-room chat-source false)
            h/touch-all
            first
            :history/body))

(cmd-hook ["that" #"^that$"]
  #"cmd" that-with-cmd-cmd
  _ that-cmd)
