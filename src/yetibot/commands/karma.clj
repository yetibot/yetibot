(ns yetibot.commands.karma
  (:require
   [yetibot.core.hooks :refer [cmd-hook]]
   [yetibot.models.karma :as model]
   [yetibot.commands.karma.specs :as karma-spec]
   [clojure.string :as str]
   [clj-time.format :as fmt]
   [clojure.spec.alpha :as s]))

(def error {:parse {:result/error "Sorry, I wasn't able to parse that."}
            :karma {:result/error "Sorry, that's not how Karma works. 🤔"}})

(defn- format-output
  [{{adapter :adapter} :chat-source} str]
  (let [formatter (condp = adapter
                    :slack #(str/replace % #"(@\w+)" "<$1>")
                    identity)]
    (formatter str)))

(defn- cmp-user-ids
  [a b]
  (let [[a b] (map #(str/replace-first % #"^@" "") [a b])]
    (= a b)))

(defn get-score
  "karma <user> # get score and recent notes for <user>"
  {:yb/cat #{:fun}}
  [ctx]
  (if-not (s/valid? ::karma-spec/get-score-ctx ctx)
    (:parse error)
    (let [{user-id :match} ctx
          score (model/get-score user-id)
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
                                          notes))))})))

(defn get-high-scores
  "karma # get leaderboard"
  {:yb/cat #{:fun}}
  [ctx]
  (if-not (s/valid? ::karma-spec/ctx ctx)
    (:parse error)
    (let [scores (model/get-high-scores)]
      {:result/data scores
       :result/value (format-output
                      ctx
                      (str/join "\n"
                                (map #(format "%s: %s" (:user-id %) (:score %))
                                     scores)))})))

(defn adjust-score
  "karma <user>(++|--) <note> # adjust karma for <user> with optional <note>"
  {:yb/cat #{:fun}}
  [ctx]
  (let [parsed (s/conform ::karma-spec/adjust-score-ctx ctx)]
    (if (= parsed ::s/invalid)
      (:parse error)
      (let [{{voter-id :id voter-name :name} :user} parsed
            {{user-id :user-id [action _] :action note :note} :match} parsed
            positive-karma? (= action :positive)]
        (if (and positive-karma? (cmp-user-ids user-id voter-id))
          (:karma error)
          (let [score-delta (if positive-karma? 1 -1)
                reply-emoji (if positive-karma? "💜" "💔")]
            (model/add-score-delta! user-id voter-name score-delta note)
            {:result/data {:user-id user-id
                           :score (model/get-score user-id)
                           :notes (model/get-notes user-id)}
             :result/value reply-emoji}))))))

(cmd-hook "karma"
          #"^@?\w[-\w]*\w$" get-score
          #"^(?x) (@?\w[-\w]*\w) \s{0,2} (--|\+\+) (?: \s+(.+) )?$" adjust-score
          _ get-high-scores)
