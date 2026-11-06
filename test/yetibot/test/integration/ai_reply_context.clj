(ns yetibot.test.integration.ai-reply-context
  (:require [clojure.string :as str]
            [discljord.messaging :as discord]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.zen]
            [yetibot.core.chat :as chat]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-model-tools :as models]
            [yetibot.test.support.ai-execution :as execution]))

(def request
  {:user {:id "U1" :username "alice"}
   :chat-source {:adapter :discord :uuid "test" :room "100"
                 :raw-event {:id "900" :channel-id "100" :author {:id "U1"}
                             :message-reference {:message-id "500"}}}
   :yetibot-user {:id "BOT"}})
(def adapter {:conn (atom {:rest :connection})})
(defn delivered [value] (doto (promise) (deliver value)))
(defn message [id author text & [parent]]
  (cond-> {:id id :channel-id "100" :content text
           :author {:id author :username author}}
    parent (assoc :message-reference {:message-id parent :channel-id "100"})))
(def parents
  {"500" (assoc (message "500" "BOT" "" "400")
                :components [{:type 17 :components
                              [{:type 10 :content "Edmonton is in Alberta."}
                               {:type 1 :components
                                [{:type 2 :label "Run again"}]}]}])
   "400" (message "400" "U1" "!zen Is Edmonton in Montana?")})
(def expected
  [{:role "user" :content "U1: !zen Is Edmonton in Montana?"}
   {:role "assistant" :content "Edmonton is in Alberta."}
   {:role "user" :content "follow up"}])
(defn error [f]
  (try (f) nil (catch Exception e (.getMessage e))))
(defn read-context
  ([] (read-context request))
  ([request]
   (execution/in-event request "ai follow up"
     #(context/messages % "follow up"))))
(def settings
  {:providers [{:name "zen" :url "https://opencode.ai/zen/v1"
                :key "fixture-zen-secret" :model "fixed-model"}]})

(fact "replies read only same-channel ancestry, oldest first, including V2 text"
  (let [calls (atom [])]
    (binding [chat/*adapter* adapter]
      (with-redefs [discord/get-channel! (fn [& _]
                                         (delivered {:id "100" :type 0}))
                    discord/get-channel-messages!
                    (fn [& _] (throw (AssertionError. "No channel history")))
                    discord/get-channel-message!
                    (fn [connection channel id]
                      (swap! calls conj [connection channel id])
                      (delivered (get parents id)))]
        (read-context) => expected
        @calls => [[:connection "100" "500"]
                   [:connection "100" "400"]]))))

(facts "cross-channel, forwarded, malformed and non-prior links do not fetch"
  (binding [chat/*adapter* adapter]
    (doseq [reference [{:message-id "500" :channel-id "other"}
                       {:message-id "500" :type 1}
                       {:message-id "../500"} {:message-id "900"} {}]]
      (with-redefs [discord/get-channel! (fn [& _]
                                           (delivered {:id "100" :type 0}))
                    discord/get-channel-message!
                    (fn [& _] (throw (AssertionError. "No unsafe lookup")))]
        (fact (str/includes?
                (str (error #(read-context
                          (assoc-in request [:chat-source :raw-event
                                             :message-reference] reference)))) "reply") => true)))))

(facts "missing, wrong-channel and wrong-ID ancestors fail without raw bodies"
  (binding [chat/*adapter* adapter]
    (doseq [reply [nil {:status 403 :body "PRIVATE-UPSTREAM"}
                   (message "499" "BOT" "wrong ID")
                   (assoc (get parents "500") :channel-id "private")]]
      (with-redefs [discord/get-channel! (fn [& _]
                                           (delivered {:id "100" :type 0}))
                    discord/get-channel-message! (fn [& _] (delivered reply))]
        (let [failure (error read-context)]
          (fact (string? failure) => true
                (str/includes? (str failure) "PRIVATE") => false))))))

(facts "reply context limits fail explicitly instead of silently truncating"
  (binding [chat/*adapter* adapter]
    (doseq [large-text? [false true]]
      (let [reads (atom 0)]
        (with-redefs [discord/get-channel! (fn [& _]
                                             (delivered {:id "100" :type 0}))
                      discord/get-channel-message!
                      (fn [_ _ id]
                        (swap! reads inc)
                        (delivered (message id "U1"
                                     (if large-text?
                                       (apply str (repeat 64001 "x")) "text")
                                     (str (dec (Long/parseLong id))))))]
          (fact (string? (error read-context)) => true
                (<= @reads 32) => true))))))

(fact "cyclic ancestry cannot repeat reads or fall back to unrelated history"
  (let [reads (atom [])]
    (binding [chat/*adapter* adapter]
      (with-redefs [discord/get-channel! (fn [& _]
                                           (delivered {:id "100" :type 0}))
                    discord/get-channel-message!
                    (fn [_ _ id]
                      (swap! reads conj id)
                      (delivered (message id "U1" "loop"
                                   (if (= id "500") "400" "500"))))]
        (string? (error read-context)) => true
        @reads => ["500" "400"]))))

(fact "untrusted reply metadata is rejected before any Discord lookup"
  (binding [chat/*adapter* adapter]
    (with-redefs [discord/get-channel!
                  (fn [& _] (throw (AssertionError. "No untrusted lookup")))]
      (str/includes? (error #(context/messages request "prompt")) "trusted")
      => true)))

(fact "edits can reread ancestry and real threads still include whole history"
  (binding [chat/*adapter* adapter]
    (with-redefs [discord/get-channel! (fn [& _]
                                       (delivered {:id "100" :type 0}))
                  discord/get-channel-message!
                  (fn [_ _ id] (delivered (get parents id)))]
      (read-context (assoc-in request [:chat-source :raw-event
                                       :edited-timestamp] "edited"))
      => expected)
    (with-redefs [discord/get-channel! (fn [& _]
                                       (delivered {:id "100" :type 12}))
                  discord/get-channel-messages!
                  (fn [& _] (delivered [(message "300" "U2" "thread text")]))
                  discord/get-channel-message!
                  (fn [& _] (throw (AssertionError. "No ancestor lookup")))]
      (read-context) => [{:role "user" :content "U2: thread text"}
                         {:role "user" :content "follow up"}])))

(fact "a timed-out ancestor read does not retry"
  (let [reads (atom 0)]
    (binding [chat/*adapter* adapter]
      (with-redefs [discord/get-channel! (fn [& _]
                                         (delivered {:id "100" :type 0}))
                    discord/get-channel-message!
                    (fn [& _]
                      (swap! reads inc)
                      (reify clojure.lang.IBlockingDeref
                        (deref [_ _ timeout-value] timeout-value)))]
        (str/includes? (error read-context) "Timed out") => true
        @reads => 1))))

(def zen-models
  (with-meta ["fixed-model"]
    {:catalog {:models [{:id "fixed-model" :interface :chat
                         :schema-summary {:api "chat-completions"}}]}}))

(fact "top-level Zen inherits context but retains single-hop/tool-free inference"
  (let [calls (atom [])]
    (binding [chat/*adapter* adapter]
      (with-redefs [ai/config (constantly settings)
                    ai/models (constantly zen-models)
                    mcp/config (constantly {:servers []})
                    discord/get-channel! (fn [& _]
                                           (delivered {:id "100" :type 0}))
                    discord/get-channel-message!
                    (fn [_ _ id] (delivered (get parents id)))
                    ai/complete
                    (fn [provider model messages options]
                      (swap! calls conj [provider model messages options])
                      {:result/value "Contextual answer"})]
        (let [result (execution/run-command request "zen follow up")]
          (get-in result [:data :text]) => "Contextual answer"
          (str/starts-with? (:value result) "Contextual answer\n\n") => true)
        (let [[provider model messages options] (first @calls)]
          messages => expected
          model => "fixed-model"
          (:native-tools provider) => []
          (:tools options) => []
          (:tool_choice options) => "none"
          (count @calls) => 1)))))

(fact "unreadable Zen conversation sends no inference and is explicitly reported"
  (let [calls (atom 0)]
    (binding [chat/*adapter* adapter]
      (with-redefs [ai/config (constantly settings)
                    mcp/config (constantly {:servers []})
                    discord/get-channel! (fn [& _] (delivered {:status 403}))
                    ai/complete (fn [& _] (swap! calls inc))]
        (let [result (execution/run-command request "zen follow up")]
          (get-in result [:data :isError]) => true
          (str/includes? (:value result) "No prompt was sent") => true
          @calls => 0)))))

(fact "native AI children remain stateless even in a reply's ambient context"
  (let [calls (atom [])]
    (with-redefs [ai/config (constantly settings)
                  ai/models (constantly zen-models)
                  context/messages (fn [& _]
                                     (throw (AssertionError. "No child history")))
                  ai/complete (fn [_ _ messages _]
                                (swap! calls conj messages)
                                {:result/value "child answer"})]
      (execution/in-event request "ai ask"
        (fn [request]
          (:isError (models/call! {} "ai"
                      {"action" "ask" "provider" "zen" "prompt" "child only"}
                      (:chat-source request))) => nil))
      @calls => [[{:role "user" :content "child only"}]])))
