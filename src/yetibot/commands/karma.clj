(ns yetibot.core.commands.karma
  (:require
   [yetibot.core.models.karma :as model]
   [clojure.string :as str]))

(defn get-user-status
  "karma <user> # get score and recent notes for <user>"
  {:yb/cat #{:fun}}
  [{{:name user-id} :user}]
  (str
   (format "%s: %s\n" user-id (model/get-score-for-user user-id))
   (str/join "\n" (map (fn [{:keys [note voter-id created-at]}]
                         (format "_\"%s\"_ --%s _(%s)_" note voter-id created-at))
                       (model/get-notes-for-user user-id)))))

(defn get-high-scores
  "karma # get leaderboard"
  {:yb/cat #{:fun}}
  []
  (str/join "\n" (map (fn [{:keys [user-id score]}]
                        (format "%s: %s" user-id score))
                      (model/get-high-scores))))

(defn adust-user
  "karma <user>(++|--) <note> # give or take karma for <user> with optional <note>"
  {:yb/cat #{:fun}}
  [{{:name voter-id} :user, args :args}]
  (let [[_ user-id action note] (re-matches #"(?:i) ([-\w])+ (--|\+\+) \s+ (.*)" args)]
    (if (= action "++")
      (if (= user-id voter-id)
        "Sorry, that's not how Karma works. :thinking_face:"
        (do 
          (model/add-score-delta user-id voter-id 1 note)
          ":purple_heart:"))
      (do
        (model/add-score-delta user-id voter-id -1 note)
        ":broken_heart:")))

(cmd-hook ["karma" #"^karma$"]
          #".+(--|\+\+)" adjust-user
          #".+" get-user-status
          _ get-high-scores)
