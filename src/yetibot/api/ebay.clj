(ns yetibot.api.ebay
  (:require
    [yetibot.config :refer [config-for-ns]]
    [yetibot.util.http :refer [encode get-json]]))

(def config (config-for-ns))

(def endpoint "http://svcs.ebay.com/services/search/FindingService/v1")

(defn find-item-endpoint [term]
  (format "%s?OPERATION-NAME=findItemsByKeywords&SERVICE-VERSION=1.0.0&SECURITY-APPNAME=%s&RESPONSE-DATA-FORMAT=JSON&REST-PAYLOAD&keywords=%s"
          endpoint
          (:app-id config)
          (encode term)))

(def find-item (comp get-json find-item-endpoint))
