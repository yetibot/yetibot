(ns yetibot.test.models.ai-wise-tools
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact facts => throws]]
            [yetibot.api.wise :as wise]
            [yetibot.models.ai-wise-tools :as tools]
            [yetibot.test.commands.wise :as fixture]
            [yetibot.test.support.ai-execution :as chat]
            [yetibot.util.ai-execution :as execution]))

(defn in-chat [f]
  (chat/in-event chat/request "ai synthetic Wise request" f))

(fact "Wise discovery and validation use trusted execution, not model identity"
  (fixture/with-wise {}
    (fn [calls]
      (fact (tools/available-tools) => []
            (tools/call! {} "wise" {"action" "balance"}
                         (:chat-source chat/request))
            => {:isError true
                :structuredContent
                {:error "Wise read failed. Check access and configuration."}})
      (in-chat
        (fn [{:keys [chat-source]}]
          (fact (tools/available-tools) => ["wise"]
                (tools/arguments! {} "wise" {"action" "balance"})
                => {"action" "balance"}
                (tools/call! {} "wise" {"action" "balance"}
                             (assoc chat-source :uuid "forged"))
                => {:isError true
                    :structuredContent
                    {:error
                     "Wise read failed. Check access and configuration."}})))
      (fact @calls => []))))

(fact "Raw native argument maps reject all unsupported fields and values"
  (fixture/with-wise {}
    (fn [calls]
      (in-chat
        (fn [_]
          (doseq [args [nil [] ["balance"] "balance" {:action "balance"}
                        {} {"action" "transfer"}
                        {"action" "balance" "actor" "U1"}
                        {"action" "balance" "account" "other"}
                        {"action" "balance" "profile-id" 11}
                        {"action" "balance" "days" 1}
                        {"action" "summary" "limit" 1}
                        {"action" "transactions" "days" 0}
                        {"action" "transactions" "days" 366}
                        {"action" "transactions" "days" "30"}
                        {"action" "transactions" "limit" 21}
                        {"action" "transactions" "limit" 1.0}]]
            (fact (tools/arguments! {} "wise" args) => (throws Exception)))
          (fact (tools/arguments! {} "other" {"action" "balance"})
                => (throws Exception))))
      (fact @calls => []))))

(fact "Native transactions accept real-shaped statements and stay projected"
  (fixture/with-wise {}
    (fn [_]
      (in-chat
        (fn [{:keys [chat-source]}]
          (let [r (tools/call! {} "wise"
                               {"action" "transactions" "limit" 1}
                               chat-source)]
            (fact (:isError r) => nil
                  (get-in r [:structuredContent :account]) => "team"
                  (get-in r [:structuredContent :untrusted]) => true
                  (count (get-in r [:structuredContent :transactions])) => 1
                  (string/includes? (str r) "PRIVATE") => false)))))))

(fact "Native summary accepts real-shaped statements without accountId"
  (fixture/with-wise {}
    (fn [calls]
      (in-chat
        (fn [{:keys [chat-source]}]
          (let [r (tools/call! {} "wise" {"action" "summary"} chat-source)]
            (fact (:isError r) => nil
                  (get-in r [:structuredContent :totals])
                  => [{:currency "USD" :transaction-count 2
                       :credits "10.00" :debits "3.21" :net "6.79"
                       :fees "0.04"}]
                  (string/includes? (str r) "PRIVATE") => false
                  (fixture/statement-urls @calls)
                  => [fixture/statement-url])))))))

(fact "Native malformed statement identity returns only the generic error"
  (doseq [action ["transactions" "summary"]]
    (fixture/with-wise {}
      (fn [calls]
        (fixture/with-statement-query #(dissoc % :intervalStart)
          (fn []
            (in-chat
              (fn [{:keys [chat-source]}]
                (fact (tools/call! {} "wise" {"action" action} chat-source)
                      => {:isError true
                          :structuredContent
                          {:error
                           (str "Wise read failed. "
                                "Check access and configuration.")}})))))
        (fact (fixture/statement-urls @calls) => [fixture/statement-url])))))

(fact "Native visibility fails closed for malformed config and chat identities"
  (doseq [settings [{:accounts nil} {:accounts []}
                    {:accounts [{:name "team" :group-id 42 :balance-id 301}]}
                    {:accounts [{:name "team" :profile-id "bad"
                                 :balance-id 301}]}
                    {:default-account "missing"} {:key-file "relative"}
                    {:adapters []} {:conversations []} {:users []}]]
    (fixture/with-wise settings
      (fn [calls]
        (in-chat (fn [_] (fact (tools/available-tools) => [])))
        (fact @calls => []))))
  (doseq [request [(dissoc chat/request :user)
                   (assoc-in chat/request [:user :yetibot?] true)
                   (assoc-in chat/request [:chat-source :raw-event :user]
                             "FAKE")
                   (update-in chat/request [:chat-source :raw-event]
                              dissoc :channel)
                   (assoc-in chat/request [:chat-source :uuid] "other")]]
    (fixture/with-wise {}
      (fn [calls]
        (chat/in-event request "ai request"
          (fn [_] (fact (tools/available-tools) => [])))
        (fact @calls => [])))))

(fact "Availability does not grant authority to a later invocation"
  (fixture/with-wise {}
    (fn [calls]
      (in-chat
        (fn [{:keys [chat-source]}]
          (let [configuration (wise/config)]
            (fact (tools/available-tools) => ["wise"])
            (with-redefs [wise/config
                          (constantly (assoc configuration :users ["OTHER"]))]
              (fact (tools/available-tools) => []
                    (:isError (tools/call! {} "wise" {"action" "balance"}
                                          chat-source)) => true)))))
      (fact @calls => []))))

(fact "Allowed users and Discord use canonical conversation IDs"
  (let [request {:user {:id "DU1"}
                 :chat-source {:adapter :discord :uuid "configured-name"
                               :raw-event {:author {:id "DU1"} :id "M1"
                                           :channel-id "DC1"}}}]
    (fixture/with-wise {:adapters ["discord"] :users ["DU1"]
                        :conversations ["configured-name/DC1"]}
      (fn [_]
        (chat/in-event request "ai request"
          (fn [{:keys [chat-source]}]
            (fact (tools/available-tools) => ["wise"]
                  (:isError (tools/call! {} "wise" {"action" "balance"}
                                        chat-source)) => nil)))))))

(fact "Schema advertises read-only bounded actions, not caller-selected IDs"
  (let [schema (get-in tools/definitions ["wise" :inputSchema])]
    (fact (:required schema) => ["action"]
          (:additionalProperties schema) => false
          (get-in schema [:properties :action :enum])
          => ["balance" "transactions" "summary"]
          (get-in schema [:properties :days :maximum]) => 365
          (get-in schema [:properties :limit :maximum]) => 20
          (set (keys (:properties schema)))
          => #{:action :account :days :limit}
          (count (:oneOf schema)) => 3)))

(fact "Connection selectors allow channels, never unrelated trusted actors"
  (let [request {:user {:id "DU1"}
                 :chat-source {:adapter :discord :uuid :test-discord
                               :raw-event {:author {:id "DU1"} :id "M1"
                                           :channel-id "DC1"}}}
        second-channel (assoc-in request [:chat-source :raw-event :channel-id]
                                 "DC2")
        settings {:adapters ["discord"]
                  :conversations [":test-discord/*"]}
        cases [[request settings true]
               [second-channel settings true]
               [(assoc-in request [:chat-source :uuid] :other) settings false]
               [(assoc-in request [:chat-source :uuid] :test-discord-extra)
                settings false]
               [request (assoc settings :conversations ["*"]) false]
               [request (assoc settings :conversations ["test-discord/*"])
                false]
               [request (assoc settings :users ["OTHER"]) false]
               [request (assoc settings :users ["DU1"]) true]
               [request (assoc settings :conversations [":test-discord/DC1"])
                true]
               [second-channel
                (assoc settings :conversations [":test-discord/DC1"]) false]
               [(assoc-in chat/request [:chat-source :uuid] :test-discord)
                settings false]
               [(assoc-in request [:chat-source :raw-event :author :id]
                          "FORGED") settings false]]]
    (doseq [[event config allowed?] cases]
      (fixture/with-wise config
        (fn [calls]
          (chat/in-event event "wise synthetic connection policy"
            (fn [{:keys [chat-source]}]
              (fact (tools/available-tools) => (if allowed? ["wise"] [])
                    (boolean
                      (:isError (tools/call! {} "wise" {"action" "balance"}
                                            chat-source))) => (not allowed?))))
          (fact (boolean (seq @calls)) => allowed?))))
    (fixture/with-wise settings
      (fn [calls]
        (fact (tools/available-tools) => []
              (:isError (tools/call! {} "wise" {"action" "balance"}
                                    (:chat-source request))) => true
              @calls => [])))))
