(ns yetibot.commands.karma
  (:require
   [yetibot.core.hooks :refer [cmd-hook]]
   [yetibot.models.karma :as model]
   [clojure.string :as str]
   [clj-time.format :as fmt]))

(s/def ::user-id (s/and string? #(re-matches #"@?\w[-\w]*\w" %)))
(s/def ::action (s/and string? (s/or :positive #(= "++" %)
                                     :negative #(= "--" %))))
(s/def ::note string?)

(def parse-error {:result/error "Sorry, I wasn't able to parse that."})
(def karma-error {:result/error "Sorry, that's not how Karma works. 🤔"})

(defn- format-output
  [{{adapter :adapter} :chat-source} str]
  (let [formatter (condp = adapter
                    :slack #(str/replace % #"(@\w+)" "<$1>")
                    identity)]
    (formatter str)))

(defn get-score
  "karma <user> # get score and recent notes for <user>"
  {:yb/cat #{:fun}}
  [{match :match :as ctx}]
  (let [user-id (s/conform ::user-id match)]
    (if (= user-id ::s/invalid)
      parse-error
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
                                            notes))))}))))

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

(s/def ::adjust-score (s/cat :user-id ::user-id
                             :action ::action
                             :note (s/? ::note)))

(defn adjust-score
  "karma <user>(++|--) <note> # adjust karma for <user> with optional <note>"
  {:yb/cat #{:fun}}
  [{match :match, {voter-id :id voter-name :name} :user}]
  (let [parsed (s/conform ::adjust-score match)]
    (if (= parsed ::s/invalid)
      parse-error
      (let [{user-id :user-id [action _] :action note :note} parsed
            positive-karma? (= action :positive)
            score-delta (if positive-karma? 1 -1)
            reply-emoji (if positive-karma? "💜" "💔")]
        (if (and positive-karma? (cmp-user-ids user-id voter-id))
          karma-error
          (do
            (model/add-score-delta! user-id voter-name score-delta note)
            {:result/data {:user-id user-id
                           :score (model/get-score user-id)
                           :notes (model/get-notes user-id)}
             :result/value reply-emoji}))))))

(cmd-hook "karma"
          #"^@?\w[-\w]*\w$" get-score
          #"^(?x) (@?\w[-\w]*\w) \s{0,2} (--|\+\+) (?: \s+(.+) )?$" adjust-score
          _ get-high-scores)
