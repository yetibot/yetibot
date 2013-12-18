(ns yetibot.commands.xkcd
  (:require
    [yetibot.core.util.http :refer [get-json]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def endpoint "http://xkcd.com/info.0.json")

(defn xkcd-cmd
  "xkcd # fetch current xkcd comic"
  [_]
  ((juxt :title :img :alt) (get-json endpoint)))

(cmd-hook #"xkcd"
          _ xkcd-cmd)
