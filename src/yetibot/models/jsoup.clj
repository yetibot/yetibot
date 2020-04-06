(ns yetibot.models.jsoup
  (:require
   [taoensso.timbre :refer [info warn error]]
   [clojure.string :as string])
  (:import
   (org.jsoup Jsoup)
   (org.jsoup.select Elements)
   (org.jsoup.nodes Element)))

(def ua "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/44.0.2403.89 Safari/537.36")

(defn get-page [url]
  (.get (.userAgent (Jsoup/connect url) ua)))

(defn get-elems [page selector]
  (.select page selector))

(defn get-attr [element attr]
  (condp = attr
    "text" (.text element)
    "html" (.html element)
    (.attr element attr)))
