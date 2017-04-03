(ns yetibot.commands.emoji
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]
    [clojure.data.json :as json]
    [clojure.core.memoize :as m])
  (:import
   [com.vdurmont.emoji EmojiManager]))

(defn get-all-emojis
  "get all emoji java objects"
  []
  (EmojiManager/getAll))

(defn parse-all-emojis
  "create sequence of emojis"
  []
  (map (fn [emoji] {:unicode (.getUnicode emoji)
                    :description (.getDescription emoji)
                    :aliases (.getAliases emoji)
                    :tags (.getTags emoji)
                    }) (get-all-emojis)))

(defonce emojis (m/ttl parse-all-emojis :ttl/threshold 3600000))

(defn filter-by-tag
  "filter memes by tag"
  [tag]
  (filter (fn [emoji] (some #{tag} (:tags emoji))) (emojis)))

(defn search-by-tag
  "search an emoji by its tag"
  {:yb/cat #{:fun :img :meme}}
  [{[_ tag] :match}]
  (if-let [found-emojis (filter-by-tag tag)]
    (map :unicode found-emojis)
    (str "Couldn't find any emojis with: \"" tag "\" tag")))

(cmd-hook ["emoji" #"^emoji$"]
          #"^tag-search\s(.+)$" search-by-tag )
