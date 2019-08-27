(ns yetibot.api.giphy
  (:require
    [taoensso.timbre :refer [info warn error]]
    [clj-http.client :as client]
    [clojure.core.memoize :as memo]
    [clojure.spec.alpha :as s]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util.http :refer [get-json encode]]))

(def base-url "http://api.giphy.com/v1")

(s/def ::key string?)

(s/def ::config (s/keys :req-un [::key]))

(defn config [] (get-config ::config [:giphy]))

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
