(ns yetibot.webapp.routes.images
  "Serves generated images stored in the in-memory image store."
  (:require [compojure.core :refer [GET defroutes]]
            [ring.util.response :as response])
  (:import [java.util Base64]))

(defonce image-store (atom {}))

(def ^:private max-stored-images 100)

(defn store-image!
  "Store a base64-encoded image and return its unique ID.
   Evicts the oldest entry when the store exceeds max-stored-images."
  [{:keys [data mime-type] :as image-data}]
  (let [id (str (java.util.UUID/randomUUID))]
    (swap! image-store
           (fn [store]
             (let [store (assoc store id (assoc image-data :timestamp (System/currentTimeMillis)))]
               (if (> (count store) max-stored-images)
                 (let [oldest-key (->> store
                                       (sort-by (comp :timestamp val))
                                       first
                                       key)]
                   (dissoc store oldest-key))
                 store))))
    id))

(defroutes image-routes
  (GET "/generated-images/:id.png" [id]
    (if-let [{:keys [data mime-type]} (get @image-store id)]
      (let [bytes (.decode (Base64/getDecoder) ^String data)]
        (-> (response/response (java.io.ByteArrayInputStream. bytes))
            (response/content-type (or mime-type "image/png"))
            (response/header "Cache-Control" "public, max-age=3600")))
      (response/not-found "Image not found"))))
