(ns yetibot.commands.ebay
  (:require [yetibot.api.ebay :refer [find-item]]
            [yetibot.hooks :refer [cmd-hook]]))

(defn format-listings [json]
  (let [items (-> json :findItemsByKeywordsResponse first :searchResult first :item)]
    (interleave
      (map (comp flatten
                 (juxt :title
                       (comp (partial str "$") :__value__ first :currentPrice first :sellingStatus)
                       :galleryURL
                       :viewItemURL))
           (take 10 items))
      (repeat ["--"]))))

(defn find-cmd
  "ebay <term> # search ebay listings for <term>"
  [{:keys [args]}]
  (format-listings (find-item args)))

(cmd-hook #"ebay"
          _ find-cmd)
