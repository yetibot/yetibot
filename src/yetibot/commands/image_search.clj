(ns yetibot.commands.image-search
  (:require [http.async.client :as client]
            [clojure.string :as s]
            [clojure.data.json :as json]
            [robert.hooke :as rh]
            [yetibot.util.http :refer [ensure-img-suffix]]
            [yetibot.hooks :refer [cmd-hook]]))

(def base-google-image-url "http://ajax.googleapis.com/ajax/services/search/images")
(def auth {:user "" :password ""})

(defn google-image-search [q n]
  (with-open [client (client/create-client)]
    (let [resp (client/GET client base-google-image-url
                           :query {:v "1.0", :rsz n, :q q :safe "active"})]
      (client/await resp)
      (json/read-json (client/string resp)))))

(defn fetch-image
  ([q] (fetch-image q 8))
  ([q n]
   (let [results (google-image-search q n)]
     (:results (:responseData results)))))

(defn image-cmd
  "image <query> # fetch a random result from google images"
  [{q :match}]
  (let [images (fetch-image q)]
    (if (seq images)
      (ensure-img-suffix (:url (rand-nth images)))
      (str "No images found for " q))))

(defn top-image
  "image top <query> # fetch the first image from google images"
  [{[_ q] :match}]
  (let [images (fetch-image q)]
    (if (seq images)
      (str (:url (first images)) "?campfire=.jpg")
      (str "No images found for " q))))

(cmd-hook #"image"
          #"^top\s(.*)" top-image
          #".*" image-cmd)
