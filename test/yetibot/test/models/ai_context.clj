(ns yetibot.test.models.ai-context
  (:require [clj-slack.conversations :as slack]
            [discljord.messaging :as discord]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.core.chat :as chat]
            [yetibot.models.ai-context :as context]))

(def slack-args
  {:chat-source {:adapter :slack :raw-event {:channel "C1" :thread_ts "1.0" :ts "4.0"}}
   :yetibot-user {:id "bot"}})
(def discord-args
  {:chat-source {:adapter :discord :raw-event {:channel-id "100" :id "400"}}
   :yetibot-user {:id "bot"}})

(defn delivered [value] (doto (promise) (deliver value)))
(defn discord-message [id author content]
  {:id id :author {:id author :username author} :content content})
(defn error-message [f]
  (try (f) (catch Exception e (.getMessage e))))

(facts "outside threads no history is retained or fetched from other rooms"
  (context/messages {} "hello") => [{:role "user" :content "hello"}]
  (with-redefs [slack/replies (fn [& _] (throw (Exception. "must not fetch")))]
    (context/messages {:chat-source {:adapter :slack :raw-event {:ts "1.0"}}} "hello")
    => [{:role "user" :content "hello"}])
  (binding [chat/*adapter* {:conn (atom {:rest :connection})}]
    (with-redefs [discord/get-channel! (fn [& _] (delivered {:id "100" :type 0}))
                  discord/get-channel-messages! (fn [& _] (throw (Exception. "must not fetch")))]
      (context/messages discord-args "hello") => [{:role "user" :content "hello"}])))

(fact "Slack reads every page, includes the root, sorts and deduplicates, and excludes current/future messages"
  (let [calls (atom [])]
    (binding [chat/*adapter* {:config {:token "slack-secret"}}]
      (with-redefs [slack/replies
                    (fn [connection channel ts options]
                      (swap! calls conj [connection channel ts options])
                      (if (:cursor options)
                        {:ok true :messages [{:ts "3.0" :user "other-bot" :bot_id "other" :text "other bot text"}
                                              {:ts "4.0" :user "person" :text "!ai --model m current"}
                                              {:ts "5.0" :user "person" :text "future"}]
                         :response_metadata {:next_cursor ""}}
                        {:ok true :has_more true
                         :messages [{:ts "2.0" :user "bot" :text "answer"}
                                    {:ts "1.0" :user "person" :text "root"}
                                    {:ts "2.0" :user "bot" :text "answer"}
                                    {:ts "2.5" :user "person" :text ""}]
                         :response_metadata {:next_cursor "next"}}))]
        (context/messages slack-args "current")
        => [{:role "user" :content "person: root"}
            {:role "assistant" :content "answer"}
            {:role "user" :content "other-bot: other bot text"}
            {:role "user" :content "current"}]
        (mapv #(nth % 3) @calls)
        => [{:limit "100" :latest "4.0" :inclusive "true"}
            {:limit "100" :latest "4.0" :inclusive "true" :cursor "next"}]
        (mapv #(subvec % 1 3) @calls) => [["C1" "1.0"] ["C1" "1.0"]]))))

(fact "Slack edited messages use their own timestamp and thread"
  (binding [chat/*adapter* {:config {:token "secret"}}]
    (with-redefs [slack/replies (fn [_ _ ts options]
                                 (fact ts => "1.0" (:latest options) => "4.0")
                                 {:ok true :messages [{:ts "1.0" :user "person" :text "root"}]})]
      (context/messages (assoc-in slack-args [:chat-source :raw-event]
                                 {:subtype "message_changed" :channel "C1" :ts "99.0"
                                  :message {:ts "4.0" :thread_ts "1.0"}}) "edited")
      => [{:role "user" :content "person: root"} {:role "user" :content "edited"}])))

(facts "Slack permission and incomplete pagination failures are not silently ignored"
  (binding [chat/*adapter* {:config {:token "secret"}}]
    (doseq [response [{:ok false :error "missing_scope"}
                      {:ok true :messages [] :has_more true}]]
      (with-redefs [slack/replies (constantly response)]
        (fact (string? (error-message #(context/messages slack-args "hello"))) => true)))
    (with-redefs [slack/replies (constantly {:ok true :messages []
                                            :response_metadata {:next_cursor "repeated"}})]
      (error-message #(context/messages slack-args "hello"))
      => "Slack thread pagination did not advance.")))

(fact "Discord reads more than 100 messages and fetches the starter from the parent channel"
  (let [calls (atom [])
        history (mapv #(discord-message (str %) "person" (str "message " %)) (range 200 300))]
    (binding [chat/*adapter* {:conn (atom {:rest :connection})}]
      (with-redefs [discord/get-channel! (fn [& _] (delivered {:id "100" :type 11 :parent-id "parent"}))
                    discord/get-channel-messages!
                    (fn [connection channel & options]
                      (let [options (apply hash-map options)]
                        (swap! calls conj [connection channel options])
                        (delivered (if (= "400" (:before options))
                                     (reverse history)
                                     [(discord-message "150" "bot" "answer")]))))
                    discord/get-channel-message!
                    (fn [connection channel id]
                      (fact [connection channel id] => [:connection "parent" "100"])
                      (delivered (discord-message "100" "person" "root")))]
        (let [messages (context/messages discord-args "current")]
          (fact
            (count messages) => 103
            (take 3 messages) => [{:role "user" :content "person: root"}
                                  {:role "assistant" :content "answer"}
                                  {:role "user" :content "person: message 200"}]
            (last messages) => {:role "user" :content "current"}))
        @calls => [[:connection "100" {:limit 100 :before "400"}]
                   [:connection "100" {:limit 100 :before "200"}]]))))

(facts "Discord forum and private threads do not read unrelated parent messages"
  (binding [chat/*adapter* {:conn (atom {:rest :connection})}]
    (doseq [type [10 11 12]]
      (with-redefs [discord/get-channel! (fn [& _] (delivered {:id "100" :type type :parent-id "parent"}))
                    discord/get-channel-messages! (fn [& _]
                                                    (delivered [(discord-message "100" "person" "root")
                                                                (discord-message "300" "bot" "reply")
                                                                (discord-message "400" "person" "current raw")
                                                                (discord-message "500" "person" "future")]))
                    discord/get-channel-message! (fn [& _] (throw (Exception. "must not fetch")))]
        (fact (context/messages discord-args "current")
              => [{:role "user" :content "person: root"}
                  {:role "assistant" :content "reply"}
                  {:role "user" :content "current"}])))))

(fact "Discord channel errors and timeouts fail safely"
  (binding [chat/*adapter* {:conn (atom {:rest :connection})}]
    (with-redefs [discord/get-channel! (fn [& _] (delivered {:error "no access"}))]
      (error-message #(context/messages discord-args "current"))
      => "Could not read the Discord channel.")
    (with-redefs [discord/get-channel! (fn [& _]
                                       (reify clojure.lang.IBlockingDeref
                                         (deref [_ _ timeout-value] timeout-value)))]
      (error-message #(context/messages discord-args "current"))
      => "Timed out reading the Discord thread.")
    (with-redefs [discord/get-channel! (fn [& _] (throw (Exception. "secret")))]
      (error-message #(context/messages discord-args "current"))
      => "Could not read the conversation. No prompt was sent to the AI provider.")))
