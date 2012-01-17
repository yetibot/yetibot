(ns yetibot.help
  (:require [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [clojure.data.json :as json]))

; TODO
(defn add-docs [prefix cmds]
  (println (str "adding docs for " prefix cmds))
  (map println cmds)
  (println (take 1 cmds))
  cmds)
