(ns yetibot.commands.xkcd
  (:require [yetibot.util.http :refer [get-json]]
            [yetibot.hooks :refer [cmd-hook]]))

(def endpoint "http://xkcd.com/info.0.json")

(defn xkcd-cmd
  "xkcd # fetch current xkcd comic"
  [_]
  ((juxt :title :img :alt) (get-json endpoint)))

(cmd-hook #"xkcd"
          _ xkcd-cmd)
