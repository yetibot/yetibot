(ns yetibot.commands.image-search
  (:require [http.async.client :as client]
            [clojure.contrib.string :as s]
            [yetibot.core :as core]
            [robert.hooke :as rh])
  (:use [yetibot.util]))

(def base-google-image-url "http://ajax.googleapis.com/ajax/services/search/images")

(defn google-image-search [q]
  (with-client 

  ;;; http://ajax.googleapis.com/ajax/services/search/images')
  ;;; v: "1.0", rsz: '10', q: q

  )


