(ns yetibot.commands.image-search
  (:require [http.async.client :as client]
            [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [clojure.data.json :as json]
            [robert.hooke :as rh])
  (:use [yetibot.util]))

(def base-google-image-url "http://ajax.googleapis.com/ajax/services/search/images")
(def auth {:user "" :password ""})

(defn google-image-search [q n]
  (with-open [client (client/create-client)]
    (let [resp (client/GET client base-google-image-url
                           :query {:v "1.0", :rsz n, :q q})]
      (client/await resp)
      (json/read-json (client/string resp)))))


(defn fetch-image
  ([q] (fetch-image q 8))
  ([q n]
   (let [results (google-image-search q n)]
     (:results (:responseData results)))))


(defn image-cmd
  "image <query> # fetch a random result from google images"
  [q]
  (let [images (fetch-image q)]
    (if (seq images)
      (:url (rand-nth images))
      (str "No images found for " q))))

(defn top-image
  "image top <query> # fetch the first image from google images"
  [q]
  (let [images (fetch-image q)]
    (if (seq images)
      (:url (first images))
      (str "No images found for " q))))


(cmd-hook #"image"
          #"^top\s(.*)" (top-image (nth p 1))
          #".*" (image-cmd p))

