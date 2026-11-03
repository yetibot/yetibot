(ns yetibot.test.integration.discord-thread-pipeline
  "Synthetic real parser/adapter boundary; SDK and history IO are isolated."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [discljord.messaging :as messaging]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.commands.grok]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.chat :as chat]
            [yetibot.core.commands.echo]
            [yetibot.core.commands.thread]
            [yetibot.core.db.observe :as observers]
            [yetibot.core.handler :as handler]
            [yetibot.core.interpreter :as interp]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.users :as users]
            [yetibot.core.parser :as parser]
            [yetibot.test.integration.ai-defaults :as defaults]
            [yetibot.util.ai-execution :as execution]))

(defn- receipt [v] (deliver (promise) v))
(defn- scenario [body check]
  (defaults/isolated
    (fn []
      (let [adapter (discord/make-discord {:name "native-admin-fixture"})
            conn (:conn adapter) calls (atom [])
            event {:id "100" :channel-id "200" :guild-id "300"
                   :author {:id "U1" :username "alice"} :content body}]
        (reset! conn {:rest :synthetic})
        (reset! (:yetibot-user adapter) {:id "BOT"})
        (with-redefs
          [users/users (atom {})
           channel/settings-for-chat-source (constantly {})
           observers/find-all (constantly [])
           messaging/get-channel!
           (fn [_ id] (receipt {:id id :type 0 :guild-id "300"}))
           messaging/start-thread-with-message!
           (fn [_ parent message & _]
             (swap! calls conj [:thread])
             (receipt {:id message :type 11 :parent-id parent}))
           messaging/create-message!
           (fn [_ target & options]
             (swap! calls conj [:answer target (apply hash-map options)])
             (receipt {:id "500" :channel-id target}))
           handler/record-and-run-raw
           (fn [body user bot]
             (execution/with-adapter-event
               (fn [source caller _ bot _]
                 (let [result (handler/handle-parsed-expr
                                source caller bot (parser/parser (subs body 1)))]
                   [{:result (or (:value result) (:error result))}]))
               interp/*chat-source* user :message bot {:body body}))]
          (binding [chat/*adapter* adapter]
            (let [completion (discord/handle-event
                               :message-create event conn (:yetibot-user adapter))
                  [_ port] (async/alts!! [completion (async/timeout 5000)])]
              (fact port => completion)
              (check @calls))))))))

(defn- text [calls]
  (->> (get-in (last calls) [2 :components 0 :components])
       (filter #(= 10 (:type %))) (map :content) (str/join "\n")))

(fact "native thread piping does not execute a piped admin command"
  (scenario "!echo ai default second chosen | thread"
    (fn [calls]
      (count (filter #(= :thread (first %)) calls)) => 1
      (second (last calls)) => "100"
      (str/starts-with? (text calls) "ai default second chosen") => true
      (:default-provider (ai/config)) => "first")))

(defn- grok-scenario [body check]
  (with-redefs [defaults/settings
                (update defaults/settings :providers conj
                        {:name "grok" :model "grok-fixture"
                         :url "https://grok.example/v1" :key "fixture-key"})]
    (scenario body check)))

(fact "Grok replies in the invoking channel without creating a thread"
  (grok-scenario "!grok fixture"
    (fn [calls]
      (mapv first calls) => [:answer]
      (second (last calls)) => "200"
      (str/starts-with? (text calls) "ordinary answer") => true)))

(fact "a native pipe receives Grok text instead of an early parent answer"
  (grok-scenario "!grok fixture | thread"
    (fn [calls]
      (mapv first calls) => [:thread :answer]
      (second (last calls)) => "100"
      (str/starts-with? (text calls) "ordinary answer") => true
      (str/includes? (text calls) "grok-fixture") => false)))

(fact "Grok honors explicit outer and earlier native thread delivery"
  (doseq [body ["!thread grok fixture"
               "!grok fixture | thread | grok %s"]]
    (grok-scenario body
      (fn [calls]
        (mapv first calls) => [:thread :answer]
        (second (last calls)) => "100"
        (str/starts-with? (text calls) "ordinary answer") => true))))

(fact "intermediate Grok answers are consumed by normal later pipe stages"
  (grok-scenario "!grok fixture | echo tail:%s"
    (fn [calls]
      (mapv first calls) => [:answer]
      (str/starts-with? (text calls) "tail:ordinary answer") => true
      (str/includes? (text calls) "grok-fixture") => false)))

(fact "thread delivery cannot make a downstream global control direct"
  (scenario "!echo chosen | thread | ai default second %s"
    (fn [calls]
      (count (filter #(= :thread (first %)) calls)) => 1
      (second (last calls)) => "100"
      (str/includes? (text calls)
                     "Send this AI control command directly in chat") => true
      (:default-provider (ai/config)) => "first")))

(fact "a rejected upstream control prevents the native thread stage from running"
  (scenario "!ai default second chosen | thread"
    (fn [calls]
      (count (filter #(= :thread (first %)) calls)) => 0
      (second (last calls)) => "200"
      (str/includes? (text calls)
                     "Send this AI control command directly in chat") => true
      (:default-provider (ai/config)) => "first")))
