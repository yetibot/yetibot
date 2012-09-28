(ns yetibot.commands.trollthat
  (:require [yetibot.models.history :as h])
  (:use [yetibot.util :only (cmd-hook)]))

(defn troll
  "trollthat # troll the last thing said"
  [user]
  (let [i (last (drop-last (h/items-with-user)))]
    (println "item is" i)
    (yetibot.core/parse-and-handle-command
      (str "meme troll: \""
           (:body i)
           "\" - "
           (-> i :user :name))
      user nil)))

(cmd-hook #"trollthat"
          _ (troll user))
