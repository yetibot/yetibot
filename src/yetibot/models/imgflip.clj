(ns yetibot.models.imgflip
  (:require
    [yetibot.models.jsoup :as jsoup]
    [lambdaisland.uri :as uri]
    [lambdaisland.uri.normalize :refer [normalize]]
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :as string]
    [clojure.spec.alpha :as s]
    [yetibot.core.spec :as yspec]
    [clj-http.client :as client]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util.http :refer [get-json map-to-query-string encode]]
    [clojure.core.memoize :as m]))

(s/def ::username ::yspec/non-blank-string)

(s/def ::password ::yspec/non-blank-string)

(s/def ::config (s/keys :req-un [::username ::password]))

(def config (:value (get-config ::config [:imgflip])))
(def configured? config)
(def endpoint "http://api.imgflip.com")

(defn fetch-memes [] (get-json (str endpoint "/get_memes")))
(defonce memes (m/ttl fetch-memes :ttl/threshold 3600000)) ; one hour memo
(comment
  (memes)
  (-> (memes) :data :memes count)
  )

(defn rand-meme [] (-> (memes) :data :memes rand-nth :name))

(defn- parse-base-36-int [s] (Integer/parseInt s 36))

(defn- match-meme-name
  "Remove spaces from meme name to provide great chance of match"
  [p meme]
  (or
    (re-find p (:name meme))
    (re-find p (string/replace (:name meme) #"\s" ""))))

(def res (client/get "https://imgflip.com/memesearch"
                     {:query-params {:q "icahn" :page 1}}))

;; TODO: append results of search-via-scrape to cached (memes) or use a memo/ttl

(defn parse-id-from-href [href]
  (second (re-find #"meme(?:template)?\/(\d+)\/" href)))

(defn search-via-scrape [q n]
  (let [scrape-url (-> (uri/uri "https://imgflip.com/memesearch")
                       (assoc :query (format "q=%s&page=%s" q n))
                       normalize
                       str)
        mt-box-elements (-> scrape-url
                            jsoup/get-page
                            (jsoup/get-elems ".mt-box"))]
    (->>
     mt-box-elements
     ;; filter out any memes that contain
     ;; <div class="mt-animated-label">animated</div>
     ;; since the imgflip generator API does not accept gif templates.
     (filter
      (fn [mt-box]
        (empty? (jsoup/get-elems mt-box ".mt-animated-label"))))
     (map
      (fn [mt-box]
        (let [meme-name (jsoup/get-attr (jsoup/get-elems mt-box ".mt-title a") "text")
              id (-> (jsoup/get-elems mt-box ".mt-title a")
                     (jsoup/get-attr  "href")
                     parse-id-from-href)
              url (-> (jsoup/get-elems mt-box "a img")
                      (jsoup/get-attr "src"))]
          {:name meme-name
           :url (str "https:" url)
           :id id}))
      ))))

(comment

  (parse-id-from-href "/memetemplate/170703314/jocko-eyes")
  (parse-id-from-href "/meme/238477572/Jocko-Willink")

  (parse-base-36-int "170703314")

  (search-via-scrape "jocko" 1)
  (search-via-scrape "typing kitty" 1)
  (generate-meme "260948664" "x" "y")

  (map
   #(jsoup/get-attr % "src")
   (-> "https://imgflip.com/memesearch?q=jocko"
       jsoup/get-page
       (jsoup/get-elems ".mt-box img"))))


(defn scrape-all-memes
  "Fetch Pages of Memes Until Max Number of Pages is Reached"
  ([q max-pages]
   (let [initial-memes (into [] (search-via-scrape q 1))]
     (scrape-all-memes q initial-memes 2 max-pages)))
  ([q merged-memes page-num max-pages]
   (let [new-memes (apply merge
                          merged-memes (search-via-scrape q page-num))]
     (if (< page-num max-pages) ; Set Max Number of Pages to Fetch Here
       (scrape-all-memes q new-memes (inc page-num) max-pages)
       merged-memes))))

(defn search-memes [query]
  (let [ms (-> (memes) :data :memes)
        p (re-pattern (str "(?i)" query))
        match-fn (partial match-meme-name p)
        results (let [matching-ms (filter match-fn ms)]
                  (if-not (empty? matching-ms)
                    matching-ms
                    ; fallback to search-via-scrape if cached results don't
                    ; contain a match
                    (scrape-all-memes query 3)))]
    ;; ensure we only return results with valid IDs to avoid "No template_id
    ;; specified" errors
    (filter :id results)))

(comment
  ;; NOTE: this term returns a gif on the web UI which we filter out since the
  ;; API doesn't support gif templates
  (search-memes "typing kitty")
  (search-memes "icahn")
  (scrape-all-memes "icahn" 3))

(defn generate-meme [id text0 text1]
  (get-json
    (str endpoint "/caption_image?"
         (map-to-query-string
           (merge config {:template_id id
                          :text0 text0
                          :text1 text1})))))

(comment
  (search-memes "whoa")
  (generate-meme "47202892" "x" "y")
  (generate-meme "4" "x" "y")
  (generate-meme "249356939" "x" "y")
  
  )

(defn generate-meme-by-query [query text0 & [text1]]
  (info "generate-meme-by-query" {:query query :text0 text0})
  (if-let [meme (first (search-memes query))]
    (apply generate-meme
           (into [(:id meme)]
                 (if text1
                   [text0 text1]
                   (let [spl (string/split text0 #"\s")]
                     (map (partial string/join " ")
                          (split-at (/ (count spl) 2) spl))))))
    {:success false
     :error_message (str "Couldn't find any memes for " query)}))

(comment
  (generate-meme-by-query "lipstick" "text0")
  )
