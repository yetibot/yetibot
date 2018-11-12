(ns yetibot.test.commands.karma
  (:require
   [midje.sweet :refer [namespace-state-changes with-state-changes fact => truthy]]
   [yetibot.commands.karma :refer :all]
   [yetibot.models.karma :as model]
   [yetibot.core.db :as db]
   [clj-time.core :as t]
   [clj-time.coerce :as coerce]))

(def epoch (coerce/to-long (t/now)))
(def test-user (str "test-user-" epoch))
(def test-voter (str "test-voter-" epoch))
(def test-note (str "test-note-" epoch))
(def test-score 1000000)

;; Simulate the context passed to our command handlers
(def slack-context {:chat-source {:adapter :slack}
                    :match test-user})

(namespace-state-changes (before :contents (db/start)))

(with-state-changes [(after :contents (model/delete-user! test-user))]

  ;; Setup test user
  (model/add-score-delta! test-user test-voter test-score test-note)

  ;; Our DB could have other users' scores so we make the assumption
  ;; that a high enough `test-score' will keep our `test-user' in the
  ;; leaderboard.
  (fact "high score data includes our test-user and test-score"
        (->> (get-high-scores slack-context)
             :result/data
             (filter #(= (:user-id %) test-user)))
        => [{:user-id test-user :score test-score}])

  (fact "user score includes note from voter"
        (->> (get-score slack-context)
             :result/data
             ((fn [m] (update m :notes #(-> % first (dissoc :created-at) vector)))))
        => {:user-id test-user
            :score test-score
            :notes [{:note test-note :voter-id test-voter}]}))
