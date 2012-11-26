(ns yetibot.commands.reload
  (:use [yetibot.hooks :only [cmd-hook]]))

(defn reload-cmd
  "reload # reload all of YetiBot's commands and observers"
  [_]
  (yetibot.core/reload-all-yetibot)
  "Reload complete.")

(cmd-hook #"reload"
          _ reload-cmd)
