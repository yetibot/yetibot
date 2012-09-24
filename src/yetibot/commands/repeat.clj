(ns yetibot.commands.repeat
  (:use [yetibot.util :only (cmd-hook)]))

(defn repeat-cmd
  "repeat <n> <cmd> # repeat <cmd> <n> times"
  [[_ n cmd] user]
  (repeatedly (read-string n) ; parse int
              #(yetibot.core/parse-and-handle-command
                 cmd user nil)))

(cmd-hook #"repeat"
          #"(\d+)\s(.+)" (repeat-cmd p user))
