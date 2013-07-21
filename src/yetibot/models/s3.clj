(ns yetibot.models.s3
  (:require [clojure.core.cache :as cache]))

(def cache-ttl (* 1000 60 60))
(def bucket-list-cache (atom (cache/ttl-cache-factory {} :ttl cache-ttl)))

; (if (cache/has? c :root)
;   (cache/hit c :root)
;   (cache/miss c :root (fetch-root)))
