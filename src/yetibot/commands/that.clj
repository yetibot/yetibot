(ns yetibot.commands.that
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn that-nocmd-cmd
  "that nocmd # retrieve last non-command from history"
  [{:keys [chat-source]}]
  (if-let [nocmd (h/non-cmd-items chat-source)]
    (second nocmd)
    "Couldn't find a non-command in the last 100 history items :("))

(defn that-cmd
  "that # retrieve last chat from history"
  [{:keys [chat-source]}]
  (-> (h/items-with-user chat-source)
      butlast
      last
      :body))

(cmd-hook ["that" #"^that$"]
  #"nocmd" that-nocmd-cmd
  _ that-cmd)
