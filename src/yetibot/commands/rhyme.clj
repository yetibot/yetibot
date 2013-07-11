(ns yetibot.commands.rhyme
  (:require
    [clojure.string :refer [trim]]
    [yetibot.hooks :refer [cmd-hook]]
    [yetibot.util.http :refer [get-json encode]]))

(defn endpoint [query]
  (format "http://rhymebrain.com/talk?function=getRhymes&word=%s&maxResults=50" (encode (trim query))))

(defn rhyme-cmd
  "rhyme <query> # find words rhyming with <query>"
  [{:keys [args]}]
  (->> (get-json (endpoint args))
    (map :word)
    (take 10)))

(cmd-hook #"rhyme"
          _ rhyme-cmd)
