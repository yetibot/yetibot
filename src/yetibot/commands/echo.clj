(ns yetibot.commands.echo
  (:use [yetibot.hooks :only (cmd-hook)]))

(defn echo-cmd
  "echo <text> # Echos back <text>. Useful for piping."
  [{args :args}] args)

(cmd-hook #"echo"
          _ echo-cmd)
