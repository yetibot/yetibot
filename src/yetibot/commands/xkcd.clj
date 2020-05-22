(ns yetibot.commands.xkcd
  (:require
    [yetibot.core.util.http :refer [get-json]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn- endpoint
  ([] "http://xkcd.com/info.0.json")
  ([i] (format "https://xkcd.com/%s/info.0.json" i)))

(defn- format-xkcd-response
  "Format response from xkcd"
  [json]
  ((juxt :title :img :alt) json))

(defn xkcd-cmd
  "xkcd # fetch current xkcd comic"
  {:yb/cat #{:fun :img}}
  [_]
  (format-xkcd-response (get-json (endpoint))))

(defn xkcd-idx-cmd
  "xkcd <index> # fetch xkcd number <index>"
  {:yb/cat #{:fun :img}}
  [{index :match}]
  (format-xkcd-response
    (try
      (get-json (endpoint index))
    (catch Exception _ 
      (get-json (endpoint 1969))))))

(cmd-hook #"xkcd"
          #"\d+" xkcd-idx-cmd
          _ xkcd-cmd)
