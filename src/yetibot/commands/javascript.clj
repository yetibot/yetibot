(ns yetibot.commands.javascript
  (:use [yetibot.hooks :only [cmd-hook]]
        [evaljs.core]
        [evaljs.rhino]))

(def statements (atom []))

(defn javascript-cmd
  "js <expression> # evaluate a javascript expression"
  [{expr :match}]
  (let [res (try
              (with-context (rhino-context)
                            (dorun (map evaljs @statements))
                            (evaljs expr))
              (catch Exception e e))]
    (when-not (instance? Exception res)
      (swap! statements conj expr))
    res))

(cmd-hook #"js"
          #".*" javascript-cmd)
