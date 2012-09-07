(ns yetibot.commands.javascript
  (:use [yetibot.util :only (cmd-hook)]
        [evaljs.core]
        [evaljs.rhino]))

(defn javascript-cmd
  "javascript <expression> # evaluate a javascript expression"
  [expr]
  (with-context (rhino-context)
                (evaljs expr)))

(cmd-hook #"javascript"
          #".*" (javascript-cmd p))
