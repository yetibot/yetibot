(ns yetibot.commands.meme-generator
  (:require [http.async.client :as client]
            [http.async.client.request :as req]
            [clojure.data.json :as json]
            [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [robert.hooke :as rh]
            [clojure.tools.cli :as c])
  (:use [yetibot.util]))

(def base-uri "http://version1.api.memegenerator.net/")
(def domain "http://memegenerator.net")
(def apis {:trending "Generators_Select_ByTrending"
           :popular "Generators_Select_ByPopular?pageIndex=0&pageSize=12&days=7"
           :instance-popular "Instances_Select_ByPopular"
           :create "Instance_Create"
           :search-generators "Generators_Search?q="})

(def auth {:user (System/getenv "MEME_USER")
           :password (System/getenv "MEME_PASS")})


; API calls
(def gen-trending
  "Retrieves trending generators"
  (memoize (fn []
             (get-json (str base-uri (:trending apis)) auth))))

(defn gen-popular []
  "Retrieves popular generators"
  (let [uri (str base-uri (:popular apis))]
    (println uri)
    (get-json uri auth)))

(defn instances-popular []
  "Retrieves popular instances"
  (get-json (str base-uri (:instance-popular apis) "?"
                 (map-to-query-string
                   {:languageCode "en" :pageSize 1 :days 1}))
            auth))

(defn search-generators [q]
  (get-json (str base-uri (:search-generators apis) q) auth))

(defn create-instance [query-string]
  (let [uri (str base-uri (:create apis) "?" query-string)]
    (println uri)
    (let [result (:result (get-json uri auth))]
      ; hit the instance url to force actual generation
      (with-client (:instanceUrl result) client/GET auth
                   (client/await response)
                   result))))

; Helpers
(defn extract-ids [json]
  {:generatorID (:generatorID json)
   :imageID (re-find #"\d+(?=\D*$)" (:imageUrl json))})

(defn build-instance-params [meme text1 text2]
  "Accepts `meme` arg of either:
  - meme name (performs a search for meme json)
  - an existing valid json response to avoid a search request
  text1 is required, text2 is optional. When text2 is missing, text1 is used
  in place of text2 and text1 is left empty"
  (merge (extract-ids 
           (if (string? meme)
             (first (:result (search-generators meme)))
             meme))
         auth
         {:username (:user auth)
         :languageCode 'en
         :text0 text1
         :text1 text2}))


; Chat senders
(defn chat-instance [i]
  (str domain (:instanceImageUrl i)))

(defn chat-instance-popular
  "meme popular                # list popular meme instances"
  []
  (chat-instance (first (:result (instances-popular)))))

(defn chat-meme-list [l]
  (s/join \newline (map #(:urlName %) (:result l))))

;; retry api calls - TODO add https://github.com/joegallo/robert-bruce

(defn trending-cmd
  "meme trending               # list trending generators"
  []
  (chat-meme-list
    (gen-trending)))

(defn search-cmd [term]
  "meme search <term>          # query available meme generators"
  (chat-meme-list
    (search-generators term)))

(defn generate-cmd
  "meme <instance-name> <line1> / <line2> # generate an instance"
  [inst line1 line2]
  (println (str "generate meme " inst))
  (chat-instance
    (create-instance
      (map-to-query-string
        (build-instance-params inst line1 line2)))))

(cmd-hook #"meme"
          #"^popular" (chat-instance-popular)
          #"^trending" (trending-cmd)
          #"^search\s(.+)" (search-cmd (second p))
          #"^(.+)\s(.+)\/(.+)$" (generate-cmd (nth p 1) (nth p 2) (nth p 3)))
