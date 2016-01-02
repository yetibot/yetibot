(ns yetibot.commands.scala
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [encode fetch]]))

(def endpoint "http://www.simplyscala.com/interp?bot=irc&code=")

(defn try-scala
  [expr]
  (let [uri (str endpoint (encode expr))]
    (fetch uri)))

(defn scala-cmd
  "scala <expression> # evaluate a scala expression"
  {:yb/cat #{:repl :broken}}
  [{expr :match}]
  (try-scala expr))

(cmd-hook #"scala"
          #".*" scala-cmd)
