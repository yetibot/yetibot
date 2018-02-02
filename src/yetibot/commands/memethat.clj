(ns yetibot.commands.memethat
  (:require
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [yetibot.core.models.history :as h]
    [yetibot.models.imgflip :as meme :refer [rand-meme]]
    [yetibot.commands.meme]
    [yetibot.core.hooks :refer [cmd-hook]]))

(defn- find-chat-to-memeify [chat-source]
  (last (h/last-chat-for-room chat-source false 2)))

(defn- format-chat [i] (:body i))

(defn- meme-it [chat-source meme-query]
  (if-let [chat (find-chat-to-memeify chat-source)]
    (handle-unparsed-expr (format "meme %s: %s" meme-query (format-chat chat)))
    (format "No history to meme :(")))

; <gen>that
(def genthat-pattern #"^(\w+)that$")

(defn genthat
  "<gen>that # use <foo> generator to memify the last thing said
   memethat # memeify the last thing said with random generator
   memethat angry picard # memeify the last thing said with a specific generator (allows spaces, unlike <gen>that)"
  [{:keys [cmd chat-source match]}]
  {:yb/cat #{:fun :img :meme}}
  (let [[_ gen] (re-find genthat-pattern cmd)]
    (meme-it chat-source
             (if (= "meme" gen)
               (if (empty? match) (rand-meme) match)
               gen))))

(cmd-hook ["genthat" genthat-pattern]
          _ genthat)
