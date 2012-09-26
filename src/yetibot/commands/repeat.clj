(ns yetibot.commands.repeat
  (:use [yetibot.util :only (cmd-hook)]))

(def max-repeat 10)

(defn repeat-cmd
  "repeat <n> <cmd> # repeat <cmd> <n> times"
  [[_ n cmd] user opts]
  (let [n (read-string n)]
    (when (> n max-repeat)
      (yetibot.campfire/chat-data-structure
        (str "Whoa there, " (:name user) ". I'm not gonna repeat that " n
             " times, but I'll do it " max-repeat " times.")))
    (let [n (min max-repeat n)]
      (repeatedly n ; parse int
                  #(yetibot.core/parse-and-handle-command
                     cmd user opts)))))

(cmd-hook #"repeat"
          #"(\d+)\s(.+)" (repeat-cmd p user opts))
