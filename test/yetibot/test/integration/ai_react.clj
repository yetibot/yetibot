(ns yetibot.test.integration.ai-react
  (:require [midje.sweet :refer [fact facts =>]]
            [discljord.messaging :as messaging]
            [yetibot.core.adapters.adapter :as adapters]
            [yetibot.models.ai-native-tools :as native]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.util.ai-execution :as context]))

(def request
  {:user {:id "U1" :username "alice"}
   :chat-source {:adapter :discord :uuid "discord-test" :room "C1"
                 :raw-event {:id "100" :channel-id "C1"
                             :author {:id "U1" :bot false}}}})
(def provider {:name "fixture" :url "http://localhost/v1"})
(def config {:toolset {:name "react" :native-tools ["react"]
                       :users ["U1"] :adapters ["discord"]
                       :providers [{:name "fixture" :models ["parent"]}]}})

(defn with-tool [f]
  (let [calls (atom [])]
    (with-redefs [adapters/adapters
                  (atom {"discord-test" {:conn (atom {:rest :fixture})}})
                  messaging/create-reaction!
                  (fn [& args] (swap! calls conj args)
                    (doto (promise) (deliver true)))
                  messaging/get-channel-messages!
                  (fn [& _] (throw (AssertionError. "No latest-message race")))]
      (execution/in-event request "ai react to this"
        (fn [event]
          (let [tool (first (native/tools config (policy/actor event)
                                          provider "parent"))]
            (f tool calls event)))))))

(fact "react is a typed native capability with no command interpreter"
  (with-tool
    (fn [tool _ _]
      (:name tool) => "react"
      (get-in tool [:inputSchema :required]) => ["emoji"]
      (get-in tool [:inputSchema :additionalProperties]) => false
      (get-in (native/definition "react") [:annotations :readOnlyHint])
      => false)))

(fact
  "AI reacts to the requesting message using Discord custom emoji syntax"
  (with-tool
    (fn [tool calls _]
      (let [args {"emoji" "<a:dance:123456789012345678>"}]
        ((:validate-arguments tool) args) => args
        (get-in ((:host-call tool) args) [:structuredContent :reacted]) => true
        @calls => [(list :fixture "C1" "100" "dance%3A123456789012345678")]))))

(fact "an explicit message ID still uses only the original channel"
  (with-tool
    (fn [tool calls _]
      (get-in ((:host-call tool) {"emoji" "👍" "message_id" "101"})
              [:structuredContent :message_id]) => "101"
      @calls => [(list :fixture "C1" "101" "%F0%9F%91%8D")])))

(facts "invalid or channel-redirecting arguments fail before effects"
  (doseq [args [{"emoji" "👍" "channel_id" "elsewhere"}
                {"emoji" "👍" "message_id" "../other"}
                {"emoji" ":unknown:"}
                {"emoji" "👍 | echo injected"}
                {}]]
    (with-tool
      (fn [tool calls _]
        (fact (try ((:validate-arguments tool) args) false
                   (catch Exception _ true)) => true)
        (fact (:isError ((:host-call tool) args)) => true)
        (fact @calls => [])))))

(fact "captured tool callbacks do not retain authority after provenance changes"
  (with-tool
    (fn [tool calls _]
      (binding [context/*event* nil]
        (try ((:host-call tool) {"emoji" "👍"}) false
             (catch Exception _ true)) => true)
      @calls => [])))

(fact "explicit native selection does not expose Discord reactions on Slack"
  (binding [context/*event* (assoc-in request [:chat-source :adapter] :slack)]
    (native/names config) => []))

(fact "toolset denial stops a captured reaction before any Discord call"
  (with-redefs [config (assoc-in config [:toolset :users] ["other-user"])]
    (with-tool
      (fn [tool calls _]
        (try ((:host-call tool) {"emoji" "👍"}) false
             (catch Exception _ true)) => true
        @calls => []))))

(fact "a failed dispatch is uncertain and is never retried automatically"
  (with-tool
    (fn [tool _ _]
      (let [calls (atom 0)]
        (with-redefs [messaging/create-reaction!
                      (fn [& _] (swap! calls inc)
                        (throw (Exception. "synthetic transport failure")))]
          (let [result ((:host-call tool) {"emoji" "👍"})]
            (:isError result) => true
            (:unknownOutcome result) => true
            @calls => 1))))))

(fact "Discord rejection is not reported as a successful reaction or retried"
  (with-tool
    (fn [tool _ _]
      (let [calls (atom 0)]
        (with-redefs [messaging/create-reaction!
                      (fn [& _] (swap! calls inc)
                        (doto (promise) (deliver false)))]
          (let [result ((:host-call tool) {"emoji" "👍"})]
            (:isError result) => true
            (:unknownOutcome result) => false
            @calls => 1))))))
