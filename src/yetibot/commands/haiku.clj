(ns yetibot.commands.haiku
  (:require
    [clojure.xml :as xml]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [get-json]]))

(def endpoint "http://www.randomhaiku.com/haiku.xml")

(defn haiku
  {:doc "haiku # fetch a random haiku"
   :yb/cat #{:broken :fun}}
  [_] (let [res (xml-seq (xml/parse endpoint))]
        (for [el res :when (= :line (:tag el))]
          (first (:content el)))))

(cmd-hook #"haiku"
          _ haiku)
