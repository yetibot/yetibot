(ns yetibot.commands.that
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.models.history :as h]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn that-with-cmd-cmd
  "that cmd # retrieve last command from history"
  [{:keys [chat-source]}]
  (if-let [cmds (h/cmd-only-items chat-source)]
    (-> cmds second second) ; the first result will be this cmd itself so use the second
    "Couldn't find a command in the last 100 history items :("))

(defn that-cmd
  "that # retrieve last non-command chat from history"
  [{:keys [chat-source]}]
  (if-let [nocmd (h/non-cmd-items chat-source)]
    (-> nocmd first second)
    "Couldn't find a non-command in the last 100 history items :("))

(cmd-hook ["that" #"^that$"]
  #"cmd" that-with-cmd-cmd
  _ that-cmd)
