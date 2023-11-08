(ns yetibot.commands.emoji-kitchen
  (:require [clj-http.client :as client]
            [yetibot.commands.emoji :refer [find-by-slack-emoji search-by-alias]]
            [yetibot.core.hooks :refer [cmd-hook]]))

(defn emoji-kitchen
  "ek <emoji-1> <emoji-2> # fetch an emoji from emoji kitchen"
  {:yb/cat #{:fun}}
  [{[_ slack-emoji-1 slack-emoji-2] :match}]
  (let [[{emoji-1 :unicode}] (find-by-slack-emoji slack-emoji-1)
        [{emoji-2 :unicode}] (find-by-slack-emoji slack-emoji-2)
        url "https://tenor.googleapis.com/v2/featured?key=AIzaSyACvEq5cnT7AcHpDdj64SE3TJZRhW-iHuo&client_key=emoji_kitchen_funbox&collection=emoji_kitchen_v6"
        result (client/get url
                           {:query-params {:q (str emoji-1 "_" emoji-2)}
                            :as           :json})]
    (-> result
        :body
        :results
        first
        :url)))

(comment

  (let [[{emoji-1 :unicode}] (find-by-slack-emoji ":smile:")]
        emoji-1)

  (find-by-slack-emoji ":sweet_potato:")
  (find-by-slack-emoji ":magic:")

  (emoji-kitchen {:match [nil ":smile:" ":star:"]})
  (search-by-alias {:match [nil ":magic_wand:"]})
  )
  ;; fetch an emoji from emoji kitchen

(cmd-hook
 {"ek" #"ek" "emoji-kitchen" #"emoji-kitchen"}
 #"(\S+)\s+(\S+)" emoji-kitchen)
