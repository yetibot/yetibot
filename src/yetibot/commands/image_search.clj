(ns yetibot.commands.image-search
  (:require [http.async.client :as client]
            [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [clojure.data.json :as json]
            [robert.hooke :as rh])
  (:use [yetibot.util]))

(def base-google-image-url "http://ajax.googleapis.com/ajax/services/search/images")
(def auth {:user "" :password ""})

(defn google-image-search [q]
  (with-open [client (client/create-client)]
    (let [resp (client/GET client base-google-image-url
                           :query {:v "1.0", :rsz "8", :q q})]
      (client/await resp)
      (json/read-json (client/string resp)))))

(defn image-cmd
  "image <query> # fetch a random result from google images"
  [q]
  (let [results (google-image-search q)
        images (:results (:responseData results))]
    (if (seq images)
      (:url (rand-nth images))
      (str "No images found for " q))))

(cmd-hook #"image"
          #".*" (image-cmd (first p)))

