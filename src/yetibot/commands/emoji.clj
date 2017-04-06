(ns yetibot.commands.emoji
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.hooks :refer [cmd-hook]]
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

(defonce emojis (parse-all-emojis))

(defn all-tags
  "emoji tags # return all known tags"
  {:yb/cat #{:fun :img :emoji}}
  [_]
  (-> (reduce (fn [return-set emoji]
                (let [tag-vector (:tags emoji)
                      tag-hash-set (set tag-vector)]
                  (clojure.set/union return-set tag-hash-set)))
              #{}
              emojis)
      sort))

(defn list-emojis
  "emoji list [-i] # list all known emojis

   [-i (info)] is optional, if set will return the unicode
   as well as a vector of its aliases"
  {:yb/cat #{:fun :img :emoji}}
  [{[_ info-flag] :match}]
  (let [emoji-keys (filter identity [:unicode (if info-flag :aliases)])
        select-values (comp vals select-keys)
        map-fn (fn [emoji]
                    (clojure.string/join " " (select-values emoji emoji-keys)))]
    (map map-fn emojis)))

(defn filter-by-tag
  "filter emojis by tag"
  [tag]
  (filter (fn [emoji] (some #{tag} (:tags emoji))) emojis))

(defn filter-by-description
  "filter emojis by description"
  [description]
  (if-let [sanitized-desc (if (or (empty? description)
                                  (coll? description))
                            nil
                            (str description))]
    (filter (fn [emoji]
              (re-find (re-pattern sanitized-desc)
                       (:description emoji)))
            emojis)
    '()))

(defn filter-by-alias
  "filter emojis by alias"
  [alias]
  (if-let [sanitized-alias (if (coll? alias)
                             nil
                             (str alias))]
    (filter (fn [emoji]
              (->
               (filter (fn [aliases]
                         (re-find (re-pattern
                                   (str "^" sanitized-alias "$"))
                                  aliases))
                       (:aliases emoji))
               empty?
               not))
            emojis)
    '()))

(defn search-by-tag
  "emoji tag <query> # query emojis by their tag

   [-i (info)] is optional, if set will return the unicode
   as well as a vector of its aliases"
  {:yb/cat #{:fun :img :emoji}}
  ([{[_ info-flag tag] :match}]
   (if-let [found-emojis (filter-by-tag tag)]
     (let [emoji-keys (filter identity [:unicode (if info-flag :aliases)])
           select-values (comp vals select-keys) 
           map-fn (fn [emoji]
                    (clojure.string/join " " (select-values emoji emoji-keys)))]
       (map map-fn found-emojis))
     (str "Couldn't find any emojis with: \"" tag "\" tag"))))

(defn search
  "emoji search [-i] <query> # search emojis by their description

   [-i (info)] is optional, if set will return the unicode
   as well as a vector of its aliases"
  {:yb/cat #{:fun :img :emoji}}
  ([{[_ info-flag description] :match}]
   (if-let [found-emojis (filter-by-description description)]
     (let [emoji-keys (filter identity [:unicode (if info-flag :aliases)])
           select-values (comp vals select-keys)
           map-fn (fn [emoji]
                    (clojure.string/join " " (select-values emoji emoji-keys)))]
       (map map-fn found-emojis))
     (str "Couldn't find any emojis with: \"" description "\" description"))))

(defn search-by-alias
  "emoji alias <query> # query an emoji by its alias (meant to return 1 emoji)"
  {:yb/cat #{:fun :img :emoji}}
  ([{[_ alias] :match}]
   (if-let [found-emoji (first (filter-by-alias alias))]
     [(:unicode found-emoji)]
     (str "Couldn't find any emojis with: \"" alias "\" alias"))))

(cmd-hook ["emoji" #"^emoji$"]
          #"^tags$" all-tags
          #"^list(\s-i)?$" list-emojis
          #"^tag\s(-i\s)?(.+)$" search-by-tag
          #"^search\s(-i\s)?(.+)$" search
          #"^alias\s(.+)$" search-by-alias)
