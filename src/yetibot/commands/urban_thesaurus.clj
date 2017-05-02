(ns yetibot.commands.urban-thesaurus
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [fetch]]
    [clojure.data.json :as json]))

(def endpoint "http://urbanthesaurus.org/api/related?term=")

(defn urbanthes-cmd
  "urbanthes <word> # fetch word from urban thesaurus"
  [{word :match}]
  (let [response (fetch (str endpoint word))]
    (map :word (json/read-str response :key-fn keyword))))

(cmd-hook #"urbanthes"
          _ urbanthes-cmd)
