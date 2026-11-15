(ns yetibot.test.models.ai-context
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-slack.conversations :as slack]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as command]
            [yetibot.test.support.ai-execution :as support]
            [discljord.messaging :as discord]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.core.chat :as chat]
            [yetibot.core.adapters.adapter :as adapters]
            [yetibot.core.models.users :as users]
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

(fact "Discord V2 answers remain readable in follow-up thread context"
  (binding [chat/*adapter* {:conn (atom {:rest :connection})}]
    (with-redefs [discord/get-channel! (fn [& _] (delivered {:id "100" :type 12}))
                  discord/get-channel-messages!
                  (fn [& _]
                    (delivered
                      [(assoc (discord-message "300" "bot" "")
                              :flags 32768
                              :components
                              [{:type 17 :accent-color 0x08B8CD
                                :components [{:type 10 :content "The previous answer"}
                                             {:type 1 :components
                                              [{:type 2 :label "Run again"}]}]}])]))]
      (context/messages discord-args "follow up")
      => [{:role "assistant" :content "The previous answer"}
          {:role "user" :content "follow up"}])))

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

(def history-policy
  {:name "history" :kind "search" :servers [] :native-tools ["command" "chart"]
   :users ["U1"] :adapters ["slack" "discord"]
   :providers [{:name "history" :models ["history-model"]}]})
(def history-provider {:name "history" :model "history-model" :url "https://history.invalid/v1"})

(fact "genuine threaded actors denied by current source policy cause zero physical history or inference IO"
  (doseq [kind [:slack :discord]]
    (let [reads (atom 0) sends (atom 0)
          request (assoc support/request :chat-source
                    (if (= kind :slack)
                      {:adapter :slack :uuid "workspace" :room "C1"
                       :raw-event {:user "U1" :channel "C1" :thread_ts "1.0" :ts "4.0"}}
                      {:adapter :discord :uuid "workspace" :room "100"
                       :raw-event {:author {:id "U1"} :channel-id "100" :id "400"}}))]
      (with-redefs [ai/config (constantly {:providers [history-provider]})
                    mcp/config (constantly {:servers [] :toolsets [(assoc history-policy :users ["denied"])]})
                    slack/replies (fn [& _] (swap! reads inc) {:ok true :messages []})
                    discord/get-channel! (fn [& _] (swap! reads inc) (delivered {:id "100" :type 12}))
                    discord/get-channel-messages! (fn [& _] (swap! reads inc) (delivered []))
                    http/post (fn [& _] (swap! sends inc) {:status 200 :body {}})]
        (support/in-registered-event request "ai --no-tools hello"
          (fn [event]
            (when (= kind :discord) (reset! (:conn chat/*adapter*) {:rest :connection}))
            (binding [chat/*adapter* (assoc chat/*adapter* :config {:token "history-fixture"})]
              (string? (error-message #(command/complete-prompt
                                        (command/prepare-prompt (assoc event :match "--no-tools hello"))))))
            => true))
        @reads => 0
        @sends => 0))))

(fact "authorized source reads complete ordered Slack history once before descriptor and final prompt"
  (let [reads (atom []) sends (atom [])
        request (assoc-in support/request [:chat-source :raw-event]
                          {:user "U1" :channel "C1" :thread_ts "1.0" :ts "4.0"})]
    (with-redefs [ai/config (constantly {:providers [history-provider]})
                  mcp/config (constantly {:servers [] :toolsets [history-policy]})
                  slack/replies (fn [_ _ _ options]
                                  (swap! reads conj options)
                                  (if (:cursor options)
                                    {:ok true :messages [{:ts "2.0" :user "BOT" :text "answer"}]}
                                    {:ok true :messages [{:ts "1.0" :user "U1" :text "root"}]
                                     :has_more true :response_metadata {:next_cursor "next"}}))
                  http/post (fn [_ options] (swap! sends conj (:form-params options))
                              {:status 200 :body {:choices [{:message {:role "assistant" :content "done"}}]}})]
      (support/in-registered-event request "ai hello"
        (fn [event]
          (binding [chat/*adapter* (assoc chat/*adapter* :config {:token "history-fixture"})]
            (command/complete-prompt (command/prepare-prompt
                                      (assoc event :yetibot-user {:id "BOT"}
                                             :match "hello" :data [{:label "A" :reading 2}] :raw "rows"))))))
      (count @reads) => 2
      (count @sends) => 1
      (let [messages (:messages (first @sends)) tail (vec (take-last 5 messages))]
        (subvec tail 0 2) => [{:role "user" :content "U1: root"} {:role "assistant" :content "answer"}]
        (:source (json/parse-string (:content (nth tail 3)) true)) => "yetibot-pipeline-dataset"
        (last tail) => {:role "user" :content "hello"}))))

(fact "prepared live thread requests reauthorize before any protected history read"
  (doseq [kind [:slack :discord] revoked [:adapter :user]]
    (let [reads (atom 0) sends (atom 0)
          request (assoc support/request :chat-source
                    (if (= kind :slack)
                      {:adapter :slack :uuid "workspace" :room "C1"
                       :raw-event {:user "U1" :channel "C1" :thread_ts "1.0" :ts "4.0"}}
                      {:adapter :discord :uuid "workspace" :room "100"
                       :raw-event {:author {:id "U1"} :channel-id "100" :id "400"}}))]
      (with-redefs [ai/config (constantly {:providers [history-provider]})
                    mcp/config (constantly {:servers [] :toolsets [history-policy]})
                    slack/replies (fn [& _] (swap! reads inc) {:ok true :messages []})
                    discord/get-channel! (fn [& _] (swap! reads inc) (delivered {:id "100" :type 12}))
                    discord/get-channel-messages! (fn [& _] (swap! reads inc) (delivered []))
                    http/post (fn [& _] (swap! sends inc) {:status 200 :body {}})]
        (support/in-registered-event request "ai --no-tools hello"
          (fn [event]
            (when (= kind :discord) (reset! (:conn chat/*adapter*) {:rest :connection}))
            (binding [chat/*adapter* (assoc chat/*adapter* :config {:token "history-fixture"})]
              (let [prepared (command/prepare-prompt (assoc event :match "--no-tools hello"))]
                (case revoked
                  :adapter (swap! adapters/adapters dissoc "workspace")
                  :user (swap! users/users dissoc {:adapter kind :id "U1"}))
                (string? (error-message #(command/complete-prompt prepared))) => true))))
        @reads => 0
        @sends => 0))))

(fact "plaintext compatibility cannot acquire history from supplied chat source data"
  (let [reads (atom 0) sends (atom 0)
        request (assoc-in support/request [:chat-source :raw-event]
                          {:user "U1" :channel "C1" :thread_ts "1.0" :ts "4.0"})]
    (with-redefs [ai/config (constantly {:providers [history-provider]})
                  mcp/config (constantly {:servers []})
                  slack/replies (fn [& _] (swap! reads inc) {:ok true :messages []})
                  http/post (fn [& _] (swap! sends inc) {:status 200 :body {}})]
      (support/with-registered-caller request
        #(binding [chat/*adapter* (assoc chat/*adapter* :config {:token "history-fixture"})]
           (string? (error-message
                      (fn [] (command/complete-prompt
                               (command/prepare-prompt (assoc request :match "--no-tools hello"))))))
           => true))
      @reads => 0
      @sends => 0)))
