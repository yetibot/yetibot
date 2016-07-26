(ns yetibot.api.giphy
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :as s]
    [clj-http.client :as client]
    [clojure.core.memoize :as memo]
    [schema.core :as sch]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util.http :refer [get-json encode]]))

(def base-url "http://api.giphy.com/v1")

(defn config [] (get-config {:key String} [:yetibot :giphy]))

(defn configured? [] (nil? (:error (config))))

(defn api-key [] (:key (:value (config))))

(defn endpoint [path] (str base-url path))

(defn translate-endpoint [query]
  (endpoint (str "/gifs/translate?s=" (encode query) "&rating=pg-13&api_key=" (api-key))))

(defn translate [term]
  (info (translate-endpoint term))
  (get-json (translate-endpoint term)))

(defn random []
  (get-json (endpoint (str "/gifs/random?rating=pg-13&api_key=" (api-key)))))

(defn trending []
  (get-json (endpoint (str "/gifs/trending?rating=pg-13&api_key=" (api-key)))))
