(ns yetibot.commands.ascii
  (:use [yetibot.util :only [cmd-hook]]
        [yetibot.util.http :only [fetch encode]]))

(def endpoint "http://asciime.heroku.com/generate_ascii?s=")

(defn ascii
  "ascii <text> # generates ascii art representation of <text>"
  [text]
  (fetch (str endpoint (encode text))))


(cmd-hook #"ascii"
          #"^.+" (ascii p))
