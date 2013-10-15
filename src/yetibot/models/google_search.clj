(ns yetibot.models.google-search
  (:require
    [yetibot.util.http :refer [get-json map-to-query-string]]))

(def endpoint "http://ajax.googleapis.com/ajax/services/search/images")
(def configured? true)

(def ^:private format-result (juxt :url :contentNoFormatting))

(defn- fetch-image [q n]
  (let [uri (str endpoint "?" (map-to-query-string
                                {:v "1.0" :rsz n
                                 :q q :safe "active"}))]
    (get-json uri)))

(defn image-search
  ([q] (image-search q 8))
  ([q n]
   (map format-result
        (-> (fetch-image q n) :responseData :results))))
