(ns yetibot.commands.echo
  (:require
    [clojure.string :as s])
  (:use [yetibot.util :only (cmd-hook)]))

(defn echo-cmd
  "echo <text> # Echos back <text>. Useful for piping."
  [text]
  text)

(cmd-hook #"echo"
          _ (echo-cmd args))
