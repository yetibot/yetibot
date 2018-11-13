(ns yetibot.test.commands.karma
  (:require
   [midje.sweet :refer [namespace-state-changes with-state-changes facts fact => truthy]]
   [yetibot.commands.karma :refer :all]
   [yetibot.models.karma :as model]
   [yetibot.core.db :as db]
   [clj-time.core :as t]
   [clj-time.coerce :as coerce]
   [clojure.spec.alpha :as s]))

(def epoch (coerce/to-long (t/now)))
(def test-user (str "test-user-" epoch))
(def test-voter (str "test-voter-" epoch))
(def test-note (str "test-note-" epoch))
(def test-score 1000000)

;; Simulate the context passed to our command handlers
(def slack-context {:chat-source {:adapter :slack}, :user {:id test-voter :name test-voter}})

(namespace-state-changes (before :contents (db/start)))

;; Do people normally write tests for their specs?  This is my first time really using spec...
(facts specs
       (fact user-id
             (s/valid? :yetibot.commands.karma/user-id "@jereme")       => truthy
             (s/valid? :yetibot.commands.karma/user-id "jereme")        => truthy
             (s/valid? :yetibot.commands.karma/user-id "@7jereme")      => truthy
             (s/valid? :yetibot.commands.karma/user-id "@jereme7")      => truthy
             (s/valid? :yetibot.commands.karma/user-id "@jer-eme")      => truthy
             (s/valid? :yetibot.commands.karma/user-id "@--jereme") =not=> truthy
             (s/valid? :yetibot.commands.karma/user-id "@jereme--") =not=> truthy)

       (fact action
             (first (s/conform :yetibot.commands.karma/action "++")) => :positive
             (first (s/conform :yetibot.commands.karma/action "--")) => :negative
             (s/valid? :yetibot.commands.karma/action "+-")      =not=> truthy)

       (fact note
             (s/valid? :yetibot.commands.karma/note "The quick brown fox") => truthy
             (s/valid? :yetibot.commands.karma/note 3.1415)            =not=> truthy))

(with-state-changes [(after :facts (model/delete-user! test-user))]

  ;; Our DB could have other users' scores so we make the assumption
  ;; that a high enough `test-score' will keep our `test-user' in the
  ;; leaderboard.
  (fact get-score
        (model/add-score-delta! test-user test-voter test-score test-note)
        (let [data (-> (get-score (assoc slack-context :match test-user))
                       :result/data)]
          (:user-id data)                  => test-user
          (:score data)                    => test-score
          (-> data :notes first :note)     => test-note
          (-> data :notes first :voter-id) => test-voter))

  (fact get-high-score
        (model/add-score-delta! test-user test-voter test-score test-note)
        (let [data (->> (get-high-scores slack-context)
                        :result/data
                        (filter #(= (:user-id %) test-user))
                        first)]
          (:user-id data) => test-user
          (:score data)   => test-score))

  (fact cmp-user-ids
        (cmp-user-ids "@foo" "@foo")     => truthy
        (cmp-user-ids "foo" "foo")       => truthy
        (cmp-user-ids "@foo" "foo")      => truthy
        (cmp-user-ids "@foo" "@bar") =not=> truthy
        (cmp-user-ids "foo" "bar")   =not=> truthy
        (cmp-user-ids "@foo" "bar")  =not=> truthy)

  (fact "adjust-score can increase another users karma"
        (let [data (-> (adjust-score (assoc slack-context :match [test-user "++"]))
                       :result/data)]
          (:user-id data) => test-user
          (:score data)   => 1))

  (fact "adjust-score can increase another users karma and include a note"
        (let [data (-> (adjust-score (assoc slack-context :match [test-user "++" test-note]))
                       :result/data)]
          (:user-id data)                  => test-user
          (:score data)                    => 1
          (-> data :notes first :note)     => test-note
          (-> data :notes first :voter-id) => test-voter))

  (fact "adjust-score can decrease another users karma"
        (let [data (-> (adjust-score (assoc slack-context :match [test-user "--"]))
                       :result/data)]
          (:user-id data) => test-user
          (:score data)   => -1))

  (fact "adjust-score can decrease another users karma and include a note"
        (let [data (-> (adjust-score (assoc slack-context :match [test-user "--" test-note]))
                       :result/data)]
          ;; We don't return notes for negative karma votes
          (:user-id data) => test-user
          (:score data)   => -1))

  (fact "adjust-score allows a user to decrease their own karma"
        (let [data (-> (adjust-score (-> slack-context
                                         (assoc :match [test-user "--"])
                                         (assoc-in [:user :id] test-user)))
                       :result/data)]
          (:user-id data) => test-user
          (:score data)   => -1))

  (fact "adjust-score precludes a user from increasing their own karma"
        (let [r (adjust-score (-> slack-context
                                  (assoc :match [test-user "++"])
                                  (assoc-in [:user :id] test-user)))]
          (:result/error r) => truthy))

  (fact "adjust-score tightly parses invocations"
        (adjust-score (assoc slack-context :match (format "%s+-" test-user)))   => #(contains? % :result/error)
        (adjust-score (assoc slack-context :match (format "+-%s" test-user)))   => #(contains? % :result/error)
        (adjust-score (assoc slack-context :match (format "++%s" test-user)))   => #(contains? % :result/error)
        (adjust-score (assoc slack-context :match (format "--%s" test-user)))   => #(contains? % :result/error)
        (adjust-score (assoc slack-context :match (format "++%s++" test-user))) => #(contains? % :result/error)
        (adjust-score (assoc slack-context :match (format "++%s--" test-user))) => #(contains? % :result/error)
        (adjust-score (assoc slack-context :match (format "--%s--" test-user))) => #(contains? % :result/error)
        (adjust-score (assoc slack-context :match (format "--%s++" test-user))) => #(contains? % :result/error)
        (adjust-score (assoc slack-context :match (format "%s++--" test-user))) => #(contains? % :result/error)
        (adjust-score (assoc slack-context :match (format "%s--++" test-user))) => #(contains? % :result/error)))
