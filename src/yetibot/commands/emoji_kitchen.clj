(ns yetibot.commands.emoji-kitchen
  (:require [clj-http.client :as client]
            [clojure.string :as str]
            [yetibot.commands.emoji :refer [find-by-slack-emoji search-by-alias]]
            [taoensso.timbre :refer [info color-str]]
            [yetibot.core.hooks :refer [cmd-hook]]))

(def url "https://tenor.googleapis.com/v2/featured?key=AIzaSyACvEq5cnT7AcHpDdj64SE3TJZRhW-iHuo&client_key=emoji_kitchen_funbox&collection=emoji_kitchen_v6")

(defn fetch-emoji
  "Fetch an emoji from emoji kitchen given two unicode emoji"
  [emoji-1 emoji-2]
  (let [result (client/get url
                           {:query-params {:q (str emoji-1 "_" emoji-2)}
                            :as           :json})]
    result))

(defn hex-to-unichar
  "Convert a hex string to a unicode character"
  [hex]
  (String. (Character/toChars (Integer/parseInt hex 16))))

(comment

  (str "\ud83d\ude00")

  (.getBytes "❇️")

  ;; TODO add support for 16-bit unicode
  (let [s "❇️"]
    ;; get unicode for string
    s
    (get s 1))

  (hex-to-unichar "2747-fe0f")

  (str "\u2747\ufe0f")
  (str "\u03A9")

  (str "\ud83d\ude00")

  (hex-to-unichar "1f954")
  (str "\u1f954")
  (hex-to-unichar "1fa84")

  (str "\u1fa84")
  (str "\uD83E\uDE84")
  (fetch-emoji "🥔" ":potato:"))


(defn extract-slack-emoji
  "Given a slack event, extract the unicode emoji"
  [{:keys [blocks] :as raw-event}]
  (let [[{[first-element] :elements}] blocks]
    (->> first-element
         :elements
         (filter #(= "emoji" (:type %)))
         (map #(-> % :unicode hex-to-unichar)))))

(defn emoji-kitchen
  "ek <emoji-1> <emoji-2> # fetch an emoji from emoji kitchen"
  {:yb/cat #{:fun}}
  [{[_ arg1 arg2] :match chat-source :chat-source :as cmd-args}]
  (info (color-str :blue "emoji-kitchen " arg1 " " arg2))
  (info (pr-str (:raw-event chat-source)))
  (let [[emoji-1 emoji-2]
        ;; extract unicode from slack-specific raw event
        (if (= :slack (:adapter chat-source))
          (extract-slack-emoji (:raw-event chat-source))
          ;; assume other platforms send emoji unencoded.
          ;; TODO confirm this is the case.
          [arg1 arg2])]
    (info (color-str :blue "emoji-kitchen" emoji-1 emoji-2))
    (let [result (fetch-emoji emoji-1 emoji-2)]
      (if (-> result :body :results empty?)
        {:result/error (str "No result for " emoji-1 " " emoji-2)}
        {:result/data result
         :result/value (-> result
                           :body
                           :results
                           first
                           :url)}))))

(comment
  (str "\u1FA84")

  ;; TODO add support for multi-word emoji like this (sparkle):
  {:source_team "asdf"
   :event_ts "asdf"
   :channel "asdf"
   :type "message"
   :ts "asdf"
   :team "asdf"
   :client_msg_id "06a45eb1-7705-4441-957f-1ef2929fc40e"
   :blocks [{:type "rich_text"
             :block_id "xeTVb"
             :elements
             [{:type "rich_text_section"
               :elements [{:type "text" :text "!ek "}
                          {:type "emoji" :name "ghost" :unicode "1f47b"}
                          {:type "text" :text " "}
                          {:type "emoji" :name "sparkle" :unicode "2747-fe0f"}]}]}]
   :user_team "E05UP675D0C"
   :user "U01863PSC81"
   :suppress_notification false
   :text "!ek :ghost: :sparkle:"}

  (find-by-slack-emoji ":magic_wand:")
  (find-by-slack-emoji ":potato:")

  (let [[{emoji-1 :unicode}] (find-by-slack-emoji ":smile:")]
    emoji-1)

  (find-by-slack-emoji ":sweet_potato:")
  (find-by-slack-emoji ":magic:")

  (emoji-kitchen {:match [nil ":smile:" ":star:"]})
  (search-by-alias {:match [nil ":magic_wand:"]}))

(cmd-hook
 {"ek" #"ek" "emoji-kitchen" #"emoji-kitchen"}
 #"(\S+)\s+(\S+)" emoji-kitchen)
