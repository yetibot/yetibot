(ns yetibot.commands.javascript
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [evaljs.core :refer :all]
    [evaljs.rhino :refer :all]))

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
