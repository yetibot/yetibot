(ns yetibot.test.models.karma
  (:require
   [midje.sweet :refer [namespace-state-changes with-state-changes fact => truthy]]
   [yetibot.models.karma :refer :all]
   [yetibot.core.db :as db]
   [clj-time.core :as time]
   [clj-time.coerce :as time.coerce]))

(def epoch (time.coerce/to-long (time/now)))
(def test-user (str "test-user-" epoch))
(def test-voter (str "test-voter-" epoch))
(def test-note (str "test-note-" epoch))

(namespace-state-changes (before :contents (db/start)))

(with-state-changes [(after :facts (delete-user! test-user))]

  (fact "a non-existing user has a score of 0"
        (get-score test-user) => 0)

  (fact "a user's score can be incremented"
        (add-score-delta! test-user test-voter 1 nil)
        (get-score test-user) => 1)

  (fact "a user's score can be decremented"
        (add-score-delta! test-user test-voter -1 nil)
        (get-score test-user) => -1)

  (fact "score updates save an optional note"
        (add-score-delta! test-user test-voter 1 test-note)
        (-> (get-notes test-user) first :note) => test-note)

  (fact "score changes save voter attribution"
        (add-score-delta! test-user test-voter 1 test-note)
        (-> (get-notes test-user) first :voter-id) => test-voter)

  (fact "created-at timestamp seems reasonable"
        (add-score-delta! test-user test-voter 1 test-note)
        (let [created-at (-> (get-notes test-user) first :created-at time.coerce/to-long)
              now        (-> (time/now) time.coerce/to-long)]
          (-> (- now created-at) (< 60)) => truthy))

  (fact "get-high-scores returns at least one item"
        (add-score-delta! test-user test-voter 1 nil)
        (-> (get-high-scores) count (>= 1)) => truthy)

  (fact "get-high-scores should not includes scores of 0 or less"
        (add-score-delta! test-user test-voter 1 nil)
        (add-score-delta! test-user test-voter -1 nil)
        (->> (get-high-scores) (filter #(= (:user-id %) test-user)) count) => 0))
