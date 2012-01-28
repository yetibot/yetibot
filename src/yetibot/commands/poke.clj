(ns yetibot.commands.poke
  (:use [yetibot.util :only (cmd-hook with-client)]))

(defn do-poking
 "poke                        # NEVER do this"
  []
  "Y U POKE ME?")

(cmd-hook #"poke"
          #"^$" (do-poking))
