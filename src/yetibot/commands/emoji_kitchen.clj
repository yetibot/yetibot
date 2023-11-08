(ns yetibot.commands.emoji-kitchen
  (:require [clj-http.client :as client]
            [yetibot.commands.emoji :refer [search-by-alias]]
            [yetibot.core.hooks :refer [cmd-hook]]))

(defn emoji-kitchen
  "ek <emoji-1> <emoji-2> # fetch an emoji from emoji kitchen"
  {:yb/cat #{:fun}}
  [{[_ emoji-1 emoji-2] :match}]
  (println "Unicode:" (:unicode emoji-1) (:unicode emoji-2))
  (println (search-by-alias emoji-1) (search-by-alias emoji-2))
  (let [url "https://tenor.googleapis.com/v2/featured?key=AIzaSyACvEq5cnT7AcHpDdj64SE3TJZRhW-iHuo&client_key=emoji_kitchen_funbox&collection=emoji_kitchen_v6"]
    (-> (client/get url
                    {:query-params {:q (str (:unicode emoji-1) "_" (:unicode emoji-2))}
                     :as           :json}))))
(comment
  (emoji-kitchen {:match [nil, ":magic_wand:", ":potato:"]})
  )
  ;; fetch an emoji from emoji kitchen

(cmd-hook ["ek" #"^ek$"]
          #"(\S+)\s+(\S+)" emoji-kitchen)