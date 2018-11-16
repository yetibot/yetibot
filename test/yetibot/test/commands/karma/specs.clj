(ns yetibot.test.commands.karma.specs
  (:require
   [midje.sweet :refer [facts fact => truthy]]
   [yetibot.commands.karma.specs :as karma.spec]
   [clj-time.core :as time]
   [clj-time.coerce :as time.coerce]
   [clojure.spec.alpha :as s]))

(def epoch (time.coerce/to-long (time/now)))
(def test-user (str "test-user-" epoch))
(def test-voter (str "test-voter-" epoch))
(def test-note (str "test-note-" epoch))

;; The context passed to our command handlers
(def slack-ctx {:chat-source {:adapter :slack}, :user {:id test-voter :name test-voter}})

(facts "common specs (not yet relocated)"
       (fact ctx
             (let [invalid-ctx (assoc-in slack-ctx [:chat-source :adapter] nil)]
               (s/valid? ::karma.spec/ctx slack-ctx)             => truthy
               (s/valid? ::karma.spec/ctx invalid-ctx) =not=> truthy)))

(facts karma
       (fact user-id
             (s/valid? ::karma.spec/user-id "@jereme")       => truthy
             (s/valid? ::karma.spec/user-id "jereme")        => truthy
             (s/valid? ::karma.spec/user-id "@7jereme")      => truthy
             (s/valid? ::karma.spec/user-id "@jereme7")      => truthy
             (s/valid? ::karma.spec/user-id "@jer-eme")      => truthy
             (s/valid? ::karma.spec/user-id "@--jereme") =not=> truthy
             (s/valid? ::karma.spec/user-id "@jereme--") =not=> truthy)

       (fact action
             (first (s/conform ::karma.spec/action "++")) => :positive
             (first (s/conform ::karma.spec/action "--")) => :negative
             (s/valid? ::karma.spec/action "+-")      =not=> truthy)

       (fact note
             (s/valid? ::karma.spec/note "The quick brown fox") => truthy
             (s/valid? ::karma.spec/note 42)                =not=> truthy))
