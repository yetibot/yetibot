(ns yetibot.models.bing-search
  (:require
    [yetibot.util :refer [make-config conf-valid?]]
    [yetibot.util.http :refer [get-json map-to-query-string]]))

(def config (make-config [:BING_KEY]))
(def auth {:user "" :password (:BING_KEY config)})
(def endpoint "https://api.datamarket.azure.com/Bing/Search/Image")
(def configured? (conf-valid? config))

(def ^:private format-result (juxt :MediaUrl :Title))

(defn image-search [q]
  (let [uri (str endpoint "?" (map-to-query-string
                                {:Query (str \' q \')
                                 :$format "json"}))
        results (get-json uri auth)]
    (map format-result (-> results :d :results))))
