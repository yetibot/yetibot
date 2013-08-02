(ns yetibot.commands.ebay
  (:require [yetibot.api.ebay :refer [find-item]]
            [yetibot.hooks :refer [cmd-hook]]))

(defn price-and-title [json]
  (let [price ((comp (partial str "$") :__value__ first :currentPrice first :sellingStatus) json)
        title ((comp first :title) json)]
    (format "%s: %s" price title)))

(defn format-listings [json]
  (let [items (-> json :findItemsByKeywordsResponse first :searchResult first :item)]
    (map (comp flatten
               (juxt price-and-title
                     :viewItemURL
                     :galleryURL))
         (take 10 items))))

(defn find-cmd
  "ebay <term> # search ebay listings for <term>"
  [{:keys [args]}]
  (format-listings (find-item args)))

(cmd-hook #"ebay"
          _ find-cmd)
