(ns yetibot.test.commands.karma.specs
  (:require
   [midje.sweet :refer [facts fact => truthy]]
   [yetibot.commands.karma.specs :as k]
   [clj-time.core :as t]
   [clj-time.coerce :as coerce]
   [clojure.spec.alpha :as s]))

(def epoch (coerce/to-long (t/now)))
(def test-user (str "test-user-" epoch))
(def test-voter (str "test-voter-" epoch))
(def test-note (str "test-note-" epoch))

;; The context passed to our command handlers
(def slack-ctx {:chat-source {:adapter :slack}, :user {:id test-voter :name test-voter}})

(facts "common specs (not yet relocated)"
       (fact ctx
             (let [invalid-ctx (assoc-in slack-ctx [:chat-source :adapter] nil)]
               (s/valid? ::k/ctx slack-ctx)             => truthy
               (s/valid? ::k/ctx invalid-ctx) =not=> truthy)))

(facts karma
       (fact user-id
             (s/valid? ::k/user-id "@jereme")       => truthy
             (s/valid? ::k/user-id "jereme")        => truthy
             (s/valid? ::k/user-id "@7jereme")      => truthy
             (s/valid? ::k/user-id "@jereme7")      => truthy
             (s/valid? ::k/user-id "@jer-eme")      => truthy
             (s/valid? ::k/user-id "@--jereme") =not=> truthy
             (s/valid? ::k/user-id "@jereme--") =not=> truthy)

       (fact action
             (first (s/conform ::k/action "++")) => :positive
             (first (s/conform ::k/action "--")) => :negative
             (s/valid? ::k/action "+-")      =not=> truthy)

       (fact note
             (s/valid? ::k/note "The quick brown fox") => truthy
             (s/valid? ::k/note 42)                =not=> truthy))
