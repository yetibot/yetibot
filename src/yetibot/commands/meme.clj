(ns yetibot.commands.meme
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [yetibot.commands.scrape :refer [scrape]]
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
    (str "Failed to generate meme: " (:error_message json))))

(defn generate-cmd
  "meme <generator>: <line1> / <line2> # generate an instance"
  {:yb/cat #{:fun :img :meme}}
  [{[_ inst line1 line2] :match}]
  (instance-result
    (model/generate-meme-by-query inst line1 line2)))

(defn rand-generate-cmd
  "meme <line1> / <line2> # generate random meme"
  {:yb/cat #{:fun :img :meme}}
  [{[_ line1 line2] :match}]
  (generate-cmd {:match [nil (model/rand-meme) line1 line2]}))

(defn generate-auto-split-cmd
  "meme <generator>: <text> # autosplit <text> in half and generate the instance"
  {:yb/cat #{:fun :img :meme}}
  [{[_ inst text] :match}]
  (instance-result
    (model/generate-meme-by-query inst text)))

(defn rand-generate-auto-split-cmd
  "meme <text> # when <text> is 4 words or more, autosplit <text> in half and generate the instance; otherwise it'll fallback to meme search"
  {:yb/cat #{:fun :img :meme}}
  [{[text _] :match}]
  (generate-auto-split-cmd {:match [nil (model/rand-meme) text]}))

(defn preview-cmd
  "meme preview <term> # preview an example of the first match for <term>"
  {:yb/cat #{:fun :img :meme}}
  [{[_ term] :match}]
  (if-let [matches (model/search-memes term)]
    (urlify (-> matches first :url))
    (str "Couldn't find any memes for " term)))

(defn search-cmd
  "meme search <term> # query available meme generators"
  {:yb/cat #{:fun :img :meme}}
  [{[_ term] :match}]
  (if-let [matches (model/search-memes term)]
    (map :name matches)
    (str "Couldn't find any memes for " term)))

(defn chat-instance-popular
  "meme popular # list popular memes from imgflip.com"
  {:yb/cat #{:fun :img :meme}}
  [_]
  (map (partial str "http:")
       (scrape "https://imgflip.com" ".base-img[src!='']" "src")))

(when model/configured?
  (cmd-hook ["meme" #"^meme$"]
            #"^popular$" chat-instance-popular
            ; #"^popular\s(.+)" chat-instance-popular-for-gen
            ; #"^trending" trending-cmd
            #"^(.+?)\s*:(.+)\/(.*)$" generate-cmd
            #"^(.+?)\s*:(.+)$" generate-auto-split-cmd
            #"^preview\s+(.+)" preview-cmd
            #"^(.+)\/(.*)$" rand-generate-cmd
            #"^(\S+\s+){3,}.*" rand-generate-auto-split-cmd ; at least 4 words
            #"^(?:search\s)?(.+)" search-cmd))
