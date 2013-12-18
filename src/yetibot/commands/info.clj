(ns yetibot.commands.info
  (:require
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.core.util.http :refer [get-json encode]]))

(def endpoint "http://api.duckduckgo.com/?format=json&q=")

(defn info
  "info <topic> # retrieve info about <topic> from DuckDuckGo"
  [{topic :match}]
  (let [json (get-json (str endpoint (encode topic)))]
    (if-let [resp (-> json :RelatedTopics first :Text)]
      resp
      (str "No info available for " topic))))

(cmd-hook #"info"
          #".+" info)
