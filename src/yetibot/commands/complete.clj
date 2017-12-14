(ns yetibot.commands.complete
  (:require
    [clojure.data.xml :refer [parse]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [fetch encode]]))

(def endpoint "http://google.com/complete/search?output=toolbar&q=")

(defn parse-suggestions [xml]
  (let [xs (xml-seq xml)]
    (for [el xs :when (= :suggestion (:tag el))]
      (-> el :attrs :data))))

(defn complete
  "complete <phrase> # complete phrase from Google Complete"
  {:test #(complete {:args "why does"})}
  [{phrase :args}]
  (->> (encode phrase)
       (str endpoint)
       fetch
       java.io.StringReader.
       parse
       parse-suggestions))

(cmd-hook #"complete"
          #".+" complete)
