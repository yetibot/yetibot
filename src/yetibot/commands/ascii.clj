(ns yetibot.commands.ascii
  (:use [yetibot.util :only [cmd-hook]]
        [yetibot.util.http :only [fetch]]))

(def endpoint "http://asciime.heroku.com/generate_ascii?s=")

(defn ascii
  "ascii <text> # generates ascii art representation of <text>"
  [text]
  (fetch (str endpoint text)))


(cmd-hook #"ascii"
          #".+" (ascii p))
