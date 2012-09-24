(ns yetibot.commands.reload
  (:use [yetibot.util :only (cmd-hook)]))

(defn reload-cmd
  "reload # reload all of YetiBot's commands and observers"
  []
  (yetibot.core/load-commands-and-observers)
  "Reload complete.")

(cmd-hook #"reload"
          _ (reload-cmd))
