(ns yetibot.models.imgflip
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :as s :refer [split join]]
    [schema.core :as sch]
    [yetibot.core.schema :refer [non-empty-str]]
    [clj-http.client :as client]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util.http :refer [get-json map-to-query-string encode]]
    [clojure.core.memoize :as m]))

(def imgflip-schema
  {:username non-empty-str
   :password non-empty-str})

(def config (:value (get-config imgflip-schema [:yetibot :imgflip])))
(def configured? (config))
(def endpoint "http://api.imgflip.com")

(defn fetch-memes [] (get-json (str endpoint "/get_memes")))
(defonce memes (m/ttl fetch-memes :ttl/threshold 3600000)) ; one hour memo

(defn rand-meme [] (-> (memes) :data :memes rand-nth :name))

(defn- parse-base-36-int [s] (Integer/parseInt s 36))

(defn- match-meme-name
  "Remove spaces from meme name to provide great chance of match"
  [p meme]
  (or
    (re-find p (:name meme))
    (re-find p (s/replace (:name meme) #"\s" ""))))

; todo: append results of search-via-scrape to cached (memes)
(defn search-via-scrape [q]
  (info "search via scraping" q)
  (let [res (client/get "https://imgflip.com/memesearch" {:query-params {:q q}})]
    (->> res
         :body
         (re-seq #"alt\=\"([^\"]+)\"\s+src\=\'.+imgflip\.com\/([\w\d]+).jpg\'")
         (map (fn [[_ alt id]]
                {:name (s/replace alt #"\sMeme Template( Thumbnail)*" "")
                 :url (str "http://i.imgflip.com/" id ".jpg")
                 :id (parse-base-36-int id)})))))

(defn search-memes [query]
  (let [ms (-> (memes) :data :memes)
        p (re-pattern (str "(?i)" query))
        match-fn (partial match-meme-name p)]
    (let [matching-ms (filter match-fn ms)]
      (if-not (empty? matching-ms)
        matching-ms
        ; fallback to search-via-scrape if cached results don't contain a match
        (search-via-scrape query)))))

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

