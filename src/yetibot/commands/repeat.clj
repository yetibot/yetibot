(ns yetibot.commands.repeat
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]))

(def max-repeat 10)

(defn repeat-cmd
  "repeat <n> <cmd> # repeat <cmd> <n> times"
  [{[_ n cmd] :match user :user opts :opts}]
  (let [n (read-string n)]
    (when (> n max-repeat)
      (yetibot.core.chat/chat-data-structure (format "Shut up %s." (:name user))))
    (let [n (min max-repeat n)]
      @(future (pmap identity (repeatedly n #(yetibot.core.handler/handle-unparsed-expr cmd)))))))

(cmd-hook #"repeat"
          #"(\d+)\s(.+)" repeat-cmd)
