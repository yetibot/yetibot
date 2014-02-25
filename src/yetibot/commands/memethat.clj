(ns yetibot.commands.memethat
  (:require
    [yetibot.core.handler :refer [handle-unparsed-expr]]
    [yetibot.core.models.history :as h]
    [yetibot.models.imgflip :as meme]
    [yetibot.core.hooks :refer [cmd-hook]]))

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

(defn- format-chat [i] (:body i))

(defn- meme-it [chat-source meme-query]
  (let [chat (find-chat-to-memeify chat-source)]
    (if chat
      (handle-unparsed-expr (format "meme %s: %s" meme-query (format-chat chat)))
      (format "No history to meme :("))))

(defn rand-meme [] (-> (meme/memes) :data :memes rand-nth :name))

; <gen>that
(def genthat-pattern #"^(\w+)that$")

(defn genthat
  "<gen>that # use <foo> generator to memify the last thing said
   memethat # memeify the last thing said with random generator"
  [{:keys [cmd chat-source]}]
  (let [[_ gen] (re-find genthat-pattern cmd)]
    (meme-it chat-source (if (= "meme" gen) (rand-meme) gen))))

(cmd-hook ["genthat" genthat-pattern]
          _ genthat)
