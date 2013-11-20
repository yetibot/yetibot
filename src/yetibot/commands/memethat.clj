(ns yetibot.commands.memethat
  (:require
    [yetibot.models.history :as h]
    [yetibot.models.imgflip :as meme]
    [yetibot.hooks :refer [cmd-hook]]))

(def ^:private history-ignore [#"^\!"])

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

(defn- meme-it [chat-source meme-query]
  (let [chat (find-chat-to-memeify chat-source)]
    (if chat
      (yetibot.handler/handle-unparsed-expr
        (format "meme %s: %s" meme-query (format-chat chat)))
      (format "No history to meme :("))))

; memethat
(defn memethat
  "memethat # use a random generator from trending memes to memeify the last thing said"
  [{:keys [chat-source]}]
  (let [random-meme (-> (meme/memes) :data :memes rand-nth)]
    (meme-it chat-source (:name random-meme))))

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
