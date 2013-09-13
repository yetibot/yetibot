(ns yetibot.repl
  "Load this namespace when working with YetiBot in the REPL or during dev."
  (:require
    [yetibot.models.users :as users]
    [yetibot.loader :refer [load-commands-and-observers load-ns]]))

; use a few non-network commands for testing
(defn load-minimal []
  (require 'yetibot.commands.echo :reload)
  (require 'yetibot.commands.catfacts :reload)
  (require 'yetibot.commands.collections :reload))

(load-minimal)

(defn load-all []
  (future
    (load-commands-and-observers)))
