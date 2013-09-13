(ns yetibot.commands.repeat
  (:use [yetibot.hooks :only [cmd-hook]]))

(def max-repeat 10)

(defn repeat-cmd
  "repeat <n> <cmd> # repeat <cmd> <n> times"
  [{[_ n cmd] :match user :user opts :opts}]
  (let [n (read-string n)]
    (when (> n max-repeat)
      (yetibot.chat/chat-data-structure
        (format "Shut up %s." (:name user))))
    (let [n (min max-repeat n)]
      (repeatedly n #(yetibot.handler/handle-unparsed-expr cmd user)))))

(cmd-hook #"repeat"
          #"(\d+)\s(.+)" repeat-cmd)
