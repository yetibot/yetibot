(ns yetibot.commands.memethat
  (:require
    [taoensso.timbre :refer [info warn error]]
    [yetibot.core.handler :refer [record-and-run-raw]]
    [yetibot.core.models.history :as h]
    [yetibot.models.imgflip :as meme :refer [rand-meme]]
    [yetibot.commands.meme]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn- find-chat-to-memeify [chat-source]
  (last (h/last-chat-for-channel chat-source false)))

(defn- meme-it [chat-source user yetibot-user meme-query]
  (if-let [{:keys [body] :as chat} (find-chat-to-memeify chat-source)]
    (let [[{:keys [timeout? embedded? error? result]}]
          (record-and-run-raw (format "!meme %s: %s" meme-query body)
                              user yetibot-user
                              {:record-yetibot-response? false})]
      {:result/data {:meme meme-query
                     :history-item chat
                     :meme-result result}
       :result/value result})
    {:result/error "No history to meme"}))

; <gen>that
(def genthat-pattern #"^(\w+)that$")

(defn genthat
  "<gen>that # use <foo> generator to memify the last thing said
   memethat # memeify the last thing said with random generator
   memethat angry picard # memeify the last thing said with a specific generator (allows spaces, unlike <gen>that)"
  [{:keys [cmd user yetibot-user chat-source match]}]
  {:yb/cat #{:fun :img :meme}}
  (info "memethat chat-source" (pr-str chat-source))
  (let [[_ gen] (re-find genthat-pattern cmd)]
    (meme-it chat-source
             user yetibot-user
             (if (= "meme" gen)
               (if (empty? match) (rand-meme) match)
               gen))))

(cmd-hook ["genthat" genthat-pattern]
          _ genthat)
