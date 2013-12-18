(ns yetibot.commands.react
  (:require
    [clojure.string :as s]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [fetch ensure-img-suffix]]))

(def endpoint "http://replygif.net/random")

(def img-pattern #"(http://replygif.net/i/\d+)")

(defn- filter-images [html]
  (second (re-find img-pattern html)))

(defn- fetch-react []
  (fetch endpoint))

(defn react-cmd
  "react # fetch a random gif from the first page of reactiongifs.com"
  [_] (-> (fetch-react)
        filter-images
        ensure-img-suffix))

(cmd-hook #"react"
          _ react-cmd)
