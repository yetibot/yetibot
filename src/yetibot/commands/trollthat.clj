(ns yetibot.commands.trollthat
  (:require [yetibot.models.history :as h]
            [yetibot.commands.meme-generator :as meme])
  (:use [yetibot.util :only (cmd-hook)]))

(def ^:private history-ignore [#"^.trollthat$" #"^.memethat$"])

(defn- filter-chat
  "Return `chat-item` only if it doesn't match any regexes in `history-ignore`"
  [chat-item]
  (let [body (:body chat-item)]
    (when (every? #(nil? (re-find % body)) history-ignore)
      chat-item)))

(defn- find-chat-to-memeify []
  (some filter-chat (->> (h/items-with-user)
                      (take-last 10)
                      reverse)))

(defn- format-chat [i]
  (format "\"%s\"" (:body i)))

(defn- meme-it [gen]
  (let [chat (find-chat-to-memeify)]
    (if chat
      (yetibot.core/parse-and-handle-command
        (format "meme %s: %s" gen (format-chat chat)) nil nil)
      (format "No history to %s." (if (= gen "troll") gen "meme")))))

; troll
(defn troll
  "trollthat # troll the last thing said"
  []
  (meme-it "troll"))

(cmd-hook #"trollthat"
          _ (troll))


; alternative: memethat
(defn memethat
  "memethat # use a random generator from trending memes to memeify the last thing said"
  []
  (let [trending (:result (meme/gen-trending))
        gen (if trending (rand-nth (map :displayName trending)) "y u no")]
   (meme-it gen)))

(cmd-hook #"memethat"
          _ (memethat))
