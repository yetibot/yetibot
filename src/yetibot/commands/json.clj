(ns yetibot.commands.json
  (:require
    [taoensso.timbre :refer [debug info warn error]]
    [clojure.data.json :as json]
    [clj-http.client :as client]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn json-cmd
  "json <url> # parse json from <url>"
  [{[url] :match}]
  (info "json" url)
  (:body (client/get url {:as :json})))

(defn json-parse-cmd
  "json parse <json>"
  [{[_ text] :match}]
  (json/read-str text))

(cmd-hook #"json"
  #"parse\s+(.+)" json-parse-cmd
  #"(.+)" json-cmd)
