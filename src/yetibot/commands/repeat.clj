(ns yetibot.commands.repeat
  (:require
    [yetibot.core.interpreter :refer [handle-cmd]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def max-repeat 10)

(defn repeat-cmd
  "repeat <n> <cmd> # repeat <cmd> <n> times"
  {:yb/cat #{:util}}
  [{[_ n cmd] :match user :user opts :opts}]
  (let [n (read-string n)]
    (when (> n max-repeat)
      (yetibot.core.chat/chat-data-structure (format "LOL %s 🐴🐴" (:name user))))
    (let [n (min max-repeat n)]
      (pmap
        identity
        (repeatedly n #(handle-cmd cmd {:user user :opts opts}))))))

(cmd-hook #"repeat"
          #"(\d+)\s(.+)" repeat-cmd)
