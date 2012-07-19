(ns yetibot.commands.scala
  (:require [clojure.string :as s]
            [http.async.client :as client])
  (:use [yetibot.util]
        [clojure.java.shell :only [sh]]))

(def endpoint "http://www.simplyscala.com/interp?bot=irc&code=")

(defn try-scala
  [expr]
  (let [uri (str endpoint (encode expr))]
    (with-open [client (client/create-client)]
      (let [response (client/GET client uri)]
        (client/await response)
        (client/string response)))))

(defn scala-cmd
  "scala <expression> # evaluate a clojure expression"
  [expr]
  (try-scala expr))

(cmd-hook #"scala"
          #".*" (scala-cmd p))
