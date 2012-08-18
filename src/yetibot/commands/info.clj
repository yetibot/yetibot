(ns yetibot.commands.info
  (:use [yetibot.util :only (cmd-hook)]
        [yetibot.util.http :only (get-json encode)]))

(def endpoint "http://api.duckduckgo.com/?format=json&q=")

(defn info
  "info <topic> # retrieve info about <topic> from DuckDuckGo"
  [topic]
  (let [json (get-json (str endpoint (encode topic)))]
    (if-let [resp (-> json :RelatedTopics first :Text)]
      resp
      (str "No info available for " topic))))

(cmd-hook #"info"
          #".+" (info p))
