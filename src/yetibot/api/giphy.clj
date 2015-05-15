(ns yetibot.api.giphy
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clojure.string :as s]
    [clj-http.client :as client]
    [clojure.core.memoize :as memo]
    [yetibot.core.config :refer [get-config conf-valid?]]
    [yetibot.core.util.http :refer [get-json encode]]))

(defn config [] (get-config :yetibot :api :giphy))

(defn configured? [] (conf-valid? (config)))

(defn api-key [] (:api-key (config)))

(def base-url "http://api.giphy.com/v1")

(defn endpoint [path] (str base-url path))

(defn translate-endpoint [query]
  (endpoint (str "/gifs/translate?s=" (encode query) "&rating=pg-13&api_key=" (api-key))))

(defn translate [term]
  (info (translate-endpoint term))
  (get-json (translate-endpoint term)))

(defn random []
  (get-json (endpoint (str "/gifs/random?rating=pg-13&api_key=" (api-key)))))

(defn trending []
  (get-json (endpoint (str "/gifs/rating=pg-13&trending?api_key=" (api-key)))))
