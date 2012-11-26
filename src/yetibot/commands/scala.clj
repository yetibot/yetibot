(ns yetibot.commands.scala
  (:use [yetibot.hooks :only [cmd-hook]]
        [yetibot.util.http :only [encode fetch]]))

(def endpoint "http://www.simplyscala.com/interp?bot=irc&code=")

(defn try-scala
  [expr]
  (let [uri (str endpoint (encode expr))]
    (fetch uri)))

(defn scala-cmd
  "scala <expression> # evaluate a scala expression"
  [{expr :match}]
  (try-scala expr))

(cmd-hook #"scala"
          #".*" scala-cmd)
