(ns yetibot.api.ebay
  (:require
    [schema.core :as s]
    [yetibot.core.config :refer [get-config]]
    [yetibot.core.util.http :refer [encode get-json]]))

(defn config [] (:value (get-config {:appid s/Str} [:ebay])))

(def endpoint "http://svcs.ebay.com/services/search/FindingService/v1")

(defn find-item-endpoint [term]
  (format "%s?OPERATION-NAME=findItemsByKeywords&SERVICE-VERSION=1.0.0&SECURITY-APPNAME=%s&RESPONSE-DATA-FORMAT=JSON&REST-PAYLOAD&keywords=%s"
          endpoint
          (:appid (config))
          (encode term)))

(def find-item (comp get-json find-item-endpoint))
