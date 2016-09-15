(ns yetibot.commands.google
  (:require
    [taoensso.timbre :refer [info]]
    [clojure.string :refer [join]]
    [yetibot.api.google :as api]
    [yetibot.core.hooks :refer [cmd-hook suppress]]))

(defn search
  "google search <query> # plain google search"
  [{[_ query] :match}]
  (let [results (api/search query)]
    (if (= (count (results :items)) 0)
      "Google returned no results!!"
      (api/format-results results))))

(defn image-search
  "google image <query> # image search"
  [{[_ query] :match}]
  (let [results (api/image-search query)]
    (if (= (count (results :items)) 0)
      (str "Google image search returned no results!! \n"
           ".. check your search engine settings")
      (api/format-results results :order :image))))

(if (api/configured?)
  (cmd-hook #"google"
            #"^search\s+(.+)" search
            #"^image\s+(.+)" image-search)
  (info "Google is not configured."))
