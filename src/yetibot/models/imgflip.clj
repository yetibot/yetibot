(ns yetibot.models.imgflip
  (:require
    [clojure.string :refer [split join]]
    [yetibot.config :refer [get-config conf-valid?]]
    [yetibot.util.http :refer [get-json map-to-query-string encode]]
    [clojure.core.memoize :as m]))

(def config (get-config :yetibot :models :imgflip))
(def configured? (conf-valid? config))
(def endpoint "http://api.imgflip.com")

(defn fetch-memes [] (get-json (str endpoint "/get_memes")))
(def memes (m/ttl fetch-memes :ttl/threshold 3600000)) ; one hour memo

(defn search-memes [query]
  (let [ms (-> (memes) :data :memes)
        p (re-pattern (str "(?i)" query))]
    (let [matching-ms (filter #(re-find p (:name %)) ms)]
      (when-not (empty? matching-ms)
        matching-ms))))

(defn generate-meme [id text0 text1]
  (get-json
    (str endpoint "/caption_image?"
         (map-to-query-string
           (merge config {:template_id id
                          :text0 text0
                          :text1 text1})))))

(defn generate-meme-by-query [query text0 & [text1]]
  (if-let [meme (first (search-memes query))]
    (apply generate-meme
           (into [(:id meme)]
                 (if text1
                   [text0 text1]
                   (let [spl (split text0 #"\s")]
                     (map (partial join " ")
                          (split-at (/ (count spl) 2) spl))))))
    {:success false
     :error_message (str "Couldn't find any memes for " query)}))
