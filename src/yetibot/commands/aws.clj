(ns yetibot.commands.aws
  (:require
    [taoensso.timbre :refer [info]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.api.aws :as aws]))

(defn echo-test
      "Returns an echo for testing purpose"
      []
      (format "hey dude what's up!"))

(when (aws/configured?)
      (cmd-hook ["aws" #"aws"]
                #"echo" echo-test))

