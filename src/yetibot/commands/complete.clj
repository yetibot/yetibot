(ns yetibot.commands.complete
  (:require
    [clojure.xml :as xml]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [encode]]))

(def endpoint "http://google.com/complete/search?output=toolbar&q=")

(defn parse-suggestions [xml]
  (let [xs (xml-seq xml)]
    (for [el xs :when (= :suggestion (:tag el))]
      (-> el :attrs :data))))

(defn complete
  "complete <phrase> # complete phrase from Google Complete"
  {:test #(complete {:args "why does"})}
  [{phrase :args}]
  (parse-suggestions (xml/parse (str endpoint (encode phrase)))))

(cmd-hook #"complete"
          #".+" complete)
