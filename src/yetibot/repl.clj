(ns yetibot.repl
  "Load this namespace when working with YetiBot in the REPL or during dev."
  (:require
    [yetibot.models.users :as users]
    [yetibot.campfire :as cf]
    [yetibot.loader :refer [load-commands-and-observers load-ns]]))


; use a few non-network commands for testing
(defn load-minimal []
  (users/reset-users-from-room (cf/get-room))
  (require 'yetibot.commands.echo :reload)
  (require 'yetibot.commands.catfacts :reload)
  (require 'yetibot.commands.collections :reload))

(load-minimal)

(defn load-all []
  (future
    (load-commands-and-observers))
  (future
    (users/reset-users-from-room (cf/get-room))))
