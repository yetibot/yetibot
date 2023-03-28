(ns yetibot.commands.xkcd
  (:require
    [yetibot.core.util.http :refer [get-json]]
    [yetibot.core.hooks :refer [cmd-hook]]))

(def todays-comic-number (atom 1))

;; on startup fetch the latest comic so we know what the latest number is
(future
  (let [latest-comic (get-json "https://xkcd.com/info.0.json")]
    (reset! todays-comic-number (:num latest-comic))))

(defn- random-comic-num
  "Random integer between 1 and todays comic number"
  []
  (inc (rand-int @todays-comic-number)))

(defn- endpoint
  "Endpoints allowed by xkcd api: https://xkcd.com/json.html"
  ([] "http://xkcd.com/info.0.json")
  ([i] (format "https://xkcd.com/%s/info.0.json" i)))

(defn- format-xkcd-response
  "Format response from xkcd"
  [json]
  ((juxt :title :img :alt) json))

(defn xkcd-cmd
  "xkcd # fetch current xkcd comic"
  {:yb/cat #{:fun :img}}
  [_]
  (let [json (get-json (endpoint))]
    (reset! todays-comic-number (:num json))
    {:result/value (format-xkcd-response json)
     :result/data json}))

(defn xkcd-idx-cmd
  "xkcd <index> # fetch xkcd number <index>"
  {:yb/cat #{:fun :img}}
  [{index :match}]
  (let [json (try
               (get-json (endpoint index))
               (catch Exception _ 
                 (get-json (endpoint 1969))))]
    {:result/value (format-xkcd-response json)
     :result/data json}))

(defn xkcd-rnd-cmd
  "xkcd random # fetch random xkcd comic"
  {:yb/cat #{:fun :img}}
  [_]
  (let [json (get-json (endpoint (random-comic-num)))]
    {:result/value (format-xkcd-response json)
     :result/data json}))

(cmd-hook #"xkcd"
          #"\d+" xkcd-idx-cmd
          #"random" xkcd-rnd-cmd
          _ xkcd-cmd)
