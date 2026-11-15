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
  {:test (fn []
           (let [requests (atom [])
                 xml "<toplevel><CompleteSuggestion><suggestion data=\"why does testing help?\"/></CompleteSuggestion><CompleteSuggestion><suggestion data=\"why does XML preserve &amp; characters?\"/></CompleteSuggestion></toplevel>"
                 suggestions (with-redefs [fetch (fn [url]
                                                   (swap! requests conj url)
                                                   xml)]
                               (vec (complete {:args "why does"})))]
             (assert (= [(str endpoint (encode "why does"))] @requests))
             (assert (= ["why does testing help?"
                         "why does XML preserve & characters?"]
                        suggestions))))}
  [{phrase :args}]
  (->> (encode phrase)
       (str endpoint)
       fetch
       java.io.StringReader.
       parse
       parse-suggestions))

(cmd-hook #"complete"
          #".+" complete)
