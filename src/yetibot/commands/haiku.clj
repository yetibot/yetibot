(ns yetibot.commands.haiku
  (:require [clojure.xml :as xml]
            [yetibot.hooks :refer [cmd-hook]]
            [yetibot.util.http :refer [get-json]]))

(def endpoint "http://www.randomhaiku.com/haiku.xml")

(defn haiku
  "haiku # fetch a random haiku"
  {:test #(assert (seq? (haiku nil)))}
  [_] (let [res (xml-seq (xml/parse endpoint))]
        (for [el res :when (= :line (:tag el))]
          (first (:content el)))))

(cmd-hook #"haiku"
          _ haiku)
