(ns yetibot.commands.json
  (:require
    [taoensso.timbre :refer [debug info warn error]]
    [clojure.data.json :as json]
    [json-path :as jp]
    [clj-http.client :as client]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn json-path-cmd
  "json path <path> # select <path> against piped data structure; see https://github.com/gga/json-path for supported syntax"
  [{[_ path] :match json :opts}]
  (if (coll? json)
    (jp/at-path path json)
    (str "Not a valid json data structure:" (pr-str json))))

(defn json-cmd
  "json <url> # parse json from <url>"
  [{[url] :match}]
  (info "json" url)
  (-> (client/get url)
      :body
      (clojure.string/replace  #"\uFEFF" "")
      json/read-str))

(defn json-parse-cmd
  "json parse <json>"
  [{[_ text] :match}]
  (json/read-str text))

(cmd-hook #"json"
  #"path\s+(.+)" json-path-cmd
  #"parse\s+(.+)" json-parse-cmd
  #"(.+)" json-cmd)
