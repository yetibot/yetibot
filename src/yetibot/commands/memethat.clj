(ns yetibot.commands.memethat
  (:require [yetibot.models.history :as h]
            [yetibot.commands.meme-generator :as meme]
            [yetibot.hooks :refer [cmd-hook]]))

(def ^:private history-ignore [#"^.trollthat$" #"^.(\w+)that$"])

(defn- filter-chat
  "Return `chat-item` only if it doesn't match any regexes in `history-ignore`"
  [chat-item]
  (let [body (:body chat-item)]
    (when (every? #(nil? (re-find % body)) history-ignore)
      chat-item)))

(defn- find-chat-to-memeify [chat-source]
  (some filter-chat (->> (h/items-with-user chat-source)
                         (take-last 10)
                         reverse)))

(defn- format-chat [i]
  (:body i))

(defn- meme-it [chat-source gen]
  (let [chat (find-chat-to-memeify chat-source)]
    (if chat
      (yetibot.handler/handle-unparsed-expr
        (format "meme %s: %s" gen (format-chat chat)))
      (format "No history to %s." (if (= gen "troll") gen "meme")))))

; memethat
(defn memethat
  "memethat # use a random generator from trending memes to memeify the last thing said"
  [{:keys [chat-source]}]
  (let [trending (:result (meme/gen-trending))
        gen (if trending (rand-nth (map :displayName trending)) "y u no")]
   (meme-it chat-source gen)))

(cmd-hook #"memethat"
          _ memethat)

; <gen>that
(def genthat-pattern #"^(\w+)that$")

(defn genthat
  "<gen>that # use <foo> generator to memify the last thing said"
  [{:keys [cmd chat-source]}]
  (let [[_ gen] (re-find genthat-pattern cmd)]
    (meme-it chat-source gen)))

(cmd-hook ["<gen>that" genthat-pattern]
          _ genthat)
