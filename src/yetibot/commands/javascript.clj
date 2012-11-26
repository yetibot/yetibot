(ns yetibot.commands.javascript
  (:use [yetibot.hooks :only [cmd-hook]]
        [evaljs.core]
        [evaljs.rhino]))

(defn javascript-cmd
  "js <expression> # evaluate a javascript expression"
  [{expr :match}]
  (with-context (rhino-context)
                (evaljs expr)))

(cmd-hook #"js"
          #".*" javascript-cmd)
