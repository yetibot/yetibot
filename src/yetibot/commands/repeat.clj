(ns yetibot.commands.repeat
  (:use [yetibot.hooks :only [cmd-hook]]))

(def max-repeat 10)

(defn repeat-cmd
  "repeat <n> <cmd> # repeat <cmd> <n> times"
  [{[_ n cmd] :match user :user opts :opts}]
  (let [n (read-string n)]
    (when (> n max-repeat)
      (yetibot.campfire/chat-data-structure
        (str "Shut up " (:name user) ".")))
    (let [n (min max-repeat n)]
      (repeatedly
        n ; parse int
        #(yetibot.handler/parse-and-handle-command cmd user opts)))))

(cmd-hook #"repeat"
          #"(\d+)\s(.+)" repeat-cmd)
