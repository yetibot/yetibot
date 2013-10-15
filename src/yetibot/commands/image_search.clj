(ns yetibot.commands.image-search
  (:require [clojure.string :as s]
            [yetibot.models.google-search]
            [yetibot.models.bing-search]
            [yetibot.util.http :refer [ensure-img-suffix]]
            [yetibot.hooks :refer [cmd-hook]]))

(def
  ^{:private true
    :doc "Sequence of configured namespaces to perform image searches on"}
  engine-nss
  (filter #(deref (ns-resolve % 'configured?))
          ['yetibot.models.google-search
           'yetibot.models.bing-search]))

(defn- fetch-image
  "Search configured namespaces starting with the first and falling back to next when
   there are no results"
  ([q] (fetch-image q engine-nss))
  ([q [n & nssrest]]
   (prn "searching for " q "in" n)
   (let [search-fn (ns-resolve n 'image-search)
         res (search-fn q)]
     (if (and (empty? res) (not (empty? nssrest)))
       (fetch-image q nssrest) ; try another search engine
       res))))

(defn- mk-fetcher
  [f]
  (fn [{[_ q] :match}]
    (let [r (fetch-image q)]
      (if (empty? r)
        "No results :("
        (f r)))))

(def ^{:doc "image top <query> # fetch the first image from google images"}
  top-image
  (mk-fetcher first))

(def ^{:doc "image <query> # fetch a random result from google images"}
  image-cmd
  (mk-fetcher rand-nth))

(cmd-hook #"image"
          #"^top\s(.*)" top-image
          #"(.+)" image-cmd)
