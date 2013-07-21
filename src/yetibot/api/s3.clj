(ns yetibot.api.s3
  (:require
    [clojure.core.async :refer :all]
    [clojure.core.memoize :refer [ttl]]
    [aws.sdk.s3 :as s3]
    [clojure.core.cache :as cache]
    [clojure.string :as s]
    [yetibot.models.s3 :refer :all]
    [yetibot.util :refer [env]]))

(def cred {:access-key (:AWS_ACCESS_KEY env), :secret-key (:AWS_SECRET_KEY env)})

(defn content [path]
  (let [[bucket key] (s/split path #"\/" 2)]
    (slurp (:content (s3/get-object cred bucket key)))))

(defn put [path object]
  (let [[bucket key] (s/split path #"\/" 2)]
    (s3/put-object cred bucket key object)))

(def buckets
  (ttl (fn [] (s3/list-buckets cred)) :ttl/threshold 120000))

(defn list-objects
  [bucket & [options]]
  (s3/list-objects cred bucket options))

(def svc (list-objects "decide-svc"))
'(:bucket :objects :prefix :common-prefixes :truncated? :max-keys :marker :next-marker)
(:common-prefixes svc)
(:truncated? svc)
(count (:objects svc))

(defn populate-bucket
  "Yields the full list of objects for a given bucket. It will continue to fetch
   objects until `truncated?` is false"
  [bucket-name]
  (let [first-page (list-objects bucket-name)]
    (loop [page first-page
           objects []]
      (let [new-objects (concat objects (:objects page))]
        (if (:truncated? page)
          (do
            (prn "truncated, fetching next" (:next-marker page))
            (prn (count new-objects))
            (recur (list-objects bucket-name {:marker (:next-marker page)})
                     new-objects))
          new-objects)))))

;; prime the keys cache for each bucket
(def fetch-thread
  (future
    (let [c (chan)
          bs (take 1 (buckets))]
      ;; listen for `(count bs)` results
      (reduce
        (fn [acc _] (conj acc (<!! (go (<! c)))))
        {}
        (range (count bs)))
      ;; fetch results
      (doseq [b bs]
        (go (>! c [(:name b) (populate-bucket (:name b))]))))))


(def f-bucket (take 1 (buckets)))


(count (populate-bucket "decide-ami"))
(count (populate-bucket "decide-images"))


;; demo
; (defn t [tms]
;   (Thread/sleep tms)
;   tms)
;
; (time
;   (do
;     (def ts (let [c (chan)]
;               (dotimes [_ 10]
;                 (go (>! c (t (rand-int 2000)))))
;               (reduce (fn [acc _]
;                         (conj acc (<!! (go (<! c)))))
;                       []
;                       (range 10))))
;     (prn ts)))
