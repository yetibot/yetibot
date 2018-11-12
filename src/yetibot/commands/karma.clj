(ns yetibot.commands.karma
  (:require
   [yetibot.core.hooks :refer [cmd-hook]]
   [yetibot.models.karma :as model]
   [clojure.string :as str]
   [clj-time.format :as fmt]))

(defn- format-output
  [{{adapter :adapter} :chat-source} str]
  (let [formatter (condp = adapter
                    :slack #(str/replace % #"(@\w+)" "<$1>")
                    identity)]
    (formatter str)))

(defn get-score
  "karma <user> # get score and recent notes for <user>"
  {:yb/cat #{:fun}}
  [{user-id :match :as ctx}]
  (let [score (model/get-score user-id)
        notes (model/get-notes user-id)]
    {:result/data {:user-id user-id, :score score, :notes notes}
     :result/value (format-output
                    ctx
                    (str (format "%s: %s\n" user-id score)
                         (str/join "\n"
                                   (map #(format "_\"%s\"_ --%s _(%s)_"
                                                 (:note %)
                                                 (:voter-id %)
                                                 (fmt/unparse (fmt/formatters :mysql) (:created-at %)))
                                        notes))))}))

(defn get-high-scores
  "karma # get leaderboard"
  {:yb/cat #{:fun}}
  [ctx]
  (let [scores (model/get-high-scores)]
    {:result/data scores
     :result/value (format-output
                    ctx
                    (str/join "\n"
                              (map #(format "%s: %s" (:user-id %) (:score %))
                                   scores)))}))

(defn cmp-user-ids
  [a b]
  (let [[a b] (map #(str/replace-first % #"^@" "") [a b])]
    (= a b)))

;; 🤔 :thinking_face:
;; 💜 :purple_heart:
;; 💔 :broken_heart:

(defn adjust-score
  "karma <user>(++|--) <note> # adjust karma for <user> with optional <note>"
  {:yb/cat #{:fun}}
  [{match :match, {voter-id :id} :user}]
  (let [[_ user-id action note] (re-matches #"(?x) (@?\w[-\w]*\w) \s{0,2} (--|\+\+) (?: \s+(.+) )?" match)]
    (if-not (and user-id action)
      {:result/error "Sorry, I wasn't able to parse that. 🤔"}
      (let [positive-karma (not= action "--")
            score-delta    (if positive-karma 1 -1)
            reply-emoji    (if positive-karma "💜" "💔")]
        (if (and positive-karma (cmp-user-ids user-id voter-id))
          {:result/error "Sorry, that's not how Karma works. 🤔"}
          (do
            (model/add-score-delta! user-id voter-id score-delta note)
            {:result/data {:user-id user-id
                           :score (model/get-score user-id)
                           :notes (model/get-notes user-id)}
             :result/value reply-emoji}))))))

(cmd-hook ["karma" #"^karma$"]
          #"^(?x) ^@?\w[-\w]*\w \s{0,2} (?:--|\+\+)" adjust-score
          #"^@?\w[-\w]*\w$" get-score
          _ get-high-scores)
