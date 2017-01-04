(ns yetibot.commands.replygif
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [fetch ensure-img-suffix]]))

(def endpoint "http://replygif.net/random")

(def img-pattern #"(http://replygif.net/i/\d+)")

(defn- filter-images [html]
  (second (re-find img-pattern html)))

(defn- fetch-replygif []
  (fetch endpoint))

(defn replygif-cmd
  "replygif # fetch a gif from replygif.net/random"
  {:yb/cat #{:fun :img :gif}}
  [_] (-> (fetch-replygif)
        filter-images
        ensure-img-suffix))

(cmd-hook #"replygif"
          _ replygif-cmd)
