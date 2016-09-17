(ns yetibot.commands.google
  (:require
    [taoensso.timbre :refer [info]]
    [clojure.string :refer [join]]
    [yetibot.api.google :as api]
    [yetibot.core.hooks :refer [cmd-hook suppress]]))

(def failure-messages
  {:search "Google returned no results!!"
   :image (str "Google image search returned no results!! \n"
               ".. check your search engine settings")
   :google-died "API Credentials are invalid || Google died"})

(defn search
  "google search <query> # plain google search"
  [{[_ query] :match}]
  (let [results (api/search query)
        count (get-in results [:searchInformation
                               :totalResults])]
    (condp = count
      nil (:google-died failure-messages)
      "0" (:search failure-messages)
      (api/format-results (:items results)))))

(defn image-search
  "google image <query> # image search"
  [{[_ query] :match}]
  (let [results (api/image-search query)
        count (get-in results [:searchInformation
                               :totalResults])]
    (condp = count
      nil (:google-died failure-messages)
      "0" (:image failure-messages)
      (api/format-results (:items results) :order :image))))

(if (api/configured?)
  (cmd-hook #"google"
            #"^search\s+(.+)" search
            #"^image\s+(.+)" image-search)
  (info "Google is not configured."))
