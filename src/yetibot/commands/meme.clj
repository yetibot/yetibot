(ns yetibot.commands.meme
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.models.imgflip :as model]))

(defn- urlify
  "Imgflip likes to return urls missing the http: prefix for some reason."
  [weird-url]
  (if-not (re-find #"^http" weird-url)
    (str "http:" weird-url)
    weird-url))

(defn- instance-result [json]
  (if (:success json)
    (urlify (-> json :data  :url))
    (str "Failed to generate meme: " (-> json :error_message))))

(defn generate-cmd
  "meme <generator>: <line1> / <line2> # generate an instance"
  [{[_ inst line1 line2] :match}]
  (instance-result
    (model/generate-meme-by-query inst line1 line2)))

(defn generate-auto-split-cmd
  "meme <generator>: <text> # autosplit <text> in half and generate the
   instance"
  [{[_ inst text] :match}]
  (instance-result
    (model/generate-meme-by-query inst text)))

(defn preview-cmd
  "meme preview <term> # preview an example of the first match for <term>"
  [{[_ term] :match}]
  (if-let [matches (model/search-memes term)]
    (urlify (-> matches first :url))
    (str "Couldn't find any memes for " term)))

(defn search-cmd
  "meme search <term> # query available meme generators"
  [{[_ term] :match}]
  (if-let [matches (model/search-memes term)]
    (map :name matches)
    (str "Couldn't find any memes for " term)))

(if model/configured?
  (cmd-hook ["meme" #"^meme$"]
            ; #"^popular$" chat-instance-popular
            ; #"^popular\s(.+)" chat-instance-popular-for-gen
            ; #"^trending" trending-cmd
            #"^(.+?):(.+)\/(.*)$" generate-cmd
            #"^(.+?):(.+)$" generate-auto-split-cmd
            #"^preview\s+(.+)" preview-cmd
            #"^(?:search\s)?(.+)" search-cmd)
  (info "Imgflip is not configured for meme generation"))
