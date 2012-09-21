(ns yetibot.commands.react
  (:require [clojure.string :as s]
            [clojure.xml :as xml])
  (:use [yetibot.util :only (cmd-hook)]
        [yetibot.util.http :only (fetch)]))

(def endpoint "http://www.reactiongifs.com/?feed=rss2")

(def img-pattern #"(http://www.reactiongifs.com/wp-content/uploads/\d+/\d+/\w+\.gif)")

(defn- filter-images [html]
  (set (map second
            (re-seq img-pattern r))))

(defn- fetch-gifs []
  (let [raw-html (fetch endpoint)]
    (filter-images raw-html)))

(defn react-cmd
  "react # fetch a random gif from the first page of reactiongifs.com"
  []
  (rand-nth (vec (fetch-gifs))))

(cmd-hook #"react"
          _ (react-cmd))
