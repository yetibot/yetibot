(ns yetibot.test.api.wise-dataset
  "Scripted Wise COMPACT response shapes; no live account or transport requests."
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.wise :as wise]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-wise-tools :as tools]
            [yetibot.test.commands.wise :as fixture]
            [yetibot.test.support.ai-execution :as chat]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-datasets :as datasets]
            [yetibot.models.ai-kroki-tools :as kroki]
            [yetibot.core.util.public-result :as public]
            [yetibot.test.integration.ai-command-charts :as charts]))

(defn query [args]
  (chat/in-event chat/request "ai synthetic finance chart"
    (fn [event] (wise/query! (policy/actor event) args))))

(defn with-statement [alter f]
  (fixture/override-reply
    (fn [original url options]
      (if (string/includes? url "balance-statements")
        (fixture/response
          (alter {:query (fixture/statement-query options)
                  :accountHolder {:name "PRIVATE HOLDER"}
                  :bankDetails {:iban "PRIVATE IBAN"}
                  :transactions
                  (mapv (fn [i]
                          {:date (str (.minusSeconds
                                        (java.time.Instant/parse
                                          (get-in options [:query-params :intervalEnd]))
                                        (long (+ 1 i))))
                           :type "CREDIT" :amount {:value 0.10000001M :currency "USD"}
                           :runningBalance {:value (+ 100M (* i 0.10000001M)) :currency "USD"}
                           :totalFees {:value 0.01M :currency "USD"}
                           :details {:description "Internal move wise-fixture-token"
                                     :accountNumber "PRIVATE NUMBER"}}) (range 27))
                  :endOfStatementBalance {:value 102.60000026M :currency "USD"}}))
        (original url options))) f))

(fact "One complete observer emission precedes display limits without another fetch"
  (fixture/with-wise {}
    (fn [calls]
      (with-statement identity
        (fn []
          (let [observed (atom [])
                result (binding [wise/*dataset-observer* #(swap! observed conj %)]
                         (query {"action" "transactions" "days" 14 "limit" 20}))
                e (first @observed)]
            (count @observed) => 1
            (:transaction-count result) => 27
            (count (:transactions result)) => 20
            (:has-more result) => true
            (set (keys (first (:transactions result))))
            => #{:date :type :currency :amount :fees :description}
            (:source e) => "wise"
            (:account e) => "team"
            (:series-field e) => "balance"
            (count (:rows e)) => 27
            (get-in e [:rows 0 "amount"]) => "0.10000001"
            (get-in e [:rows 0 "running-balance"]) => "100.00000000"
            (get-in e [:rows 0 "balance"]) => "USD balance 1"
            (:historical-balance-available? e) => true
            (select-keys (:coverage e) [:status :row-count :from :to :has-more])
            => {:status :complete :row-count 27 :from (:from result) :to (:to result) :has-more false}
            (:current-balances e) => [{"balance" "USD balance 1" "currency" "USD" "available" "12.34"}]
            (:statement-balances e)
            => [{"balance" "USD balance 1" "currency" "USD" "date" (:to result) "amount" "102.60000026"}]
            (string/includes? (str e) "PRIVATE") => false
            (string/includes? (str e) "wise-fixture-token") => false
            (string/includes? (str e) "profileId") => false
            (fixture/statement-urls @calls) => [fixture/statement-url]))))))

(fact "Genuine command and native entry points retain display limits with full capture"
  (doseq [entry [:command :native]]
    (fixture/with-wise {}
      (fn [calls]
        (with-statement identity
          (fn []
            (let [observed (atom [])
                  result
                  (binding [wise/*dataset-observer* #(swap! observed conj %)]
                    (if (= entry :command)
                      (:data (fixture/run "wise transactions --days 14 --limit 20"))
                      (chat/in-event chat/request "ai synthetic statement"
                        (fn [{:keys [chat-source]}]
                          (:structuredContent
                            (tools/call! {} "wise" {"action" "transactions" "days" 14 "limit" 20}
                                         chat-source))))))]
              (:transaction-count result) => 27
              (count (:transactions result)) => 20
              (:has-more result) => true
              (count @observed) => 1
              (count (:rows (first @observed))) => 27
              (fixture/statement-urls @calls) => [fixture/statement-url])))))))

(fact "Summary emits exact full-period totals; balance emits only a current snapshot"
  (fixture/with-wise {}
    (fn [calls]
      (with-statement identity
        (fn []
          (let [observed (atom [])]
            (binding [wise/*dataset-observer* #(swap! observed conj %)]
              (query {"action" "summary"})
              (query {"action" "balance"}))
            (count @observed) => 2
            (get-in @observed [0 :rows 0 "credits"]) => "2.70000027"
            (get-in @observed [0 :rows 0 "fees"]) => "0.27"
            (get-in @observed [0 :rows 0 "transaction-count"]) => 27
            (get-in @observed [1 :rows 0 "available"]) => "12.34"
            (get-in @observed [1 :fields 2 :semantic]) => :current-balance
            (fixture/statement-urls @calls) => [fixture/statement-url]))))))

(fact "Missing, partial or malformed running balances never become historical authority"
  (doseq [alter [(fn [b] (update b :transactions #(mapv (fn [t] (dissoc t :runningBalance)) %)))
                 #(update-in % [:transactions 0] dissoc :runningBalance)
                 #(assoc-in % [:transactions 0 :runningBalance :currency] "EUR")
                 #(assoc-in % [:transactions 0 :runningBalance :value] "100.00")
                 #(assoc-in % [:transactions 0 :runningBalance :value] 1.0e100)]]
    (fixture/with-wise {}
      (fn [_]
        (with-statement alter
          (fn []
            (let [observed (atom nil)
                  r (binding [wise/*dataset-observer* #(reset! observed %)]
                      (query {"action" "transactions"}))]
              (:transaction-count r) => 27
              (:historical-balance-available? @observed) => false
              (some #(= :running-balance (:semantic %)) (:fields @observed)) => nil
              (some #(contains? % "running-balance") (:rows @observed)) => nil
              (boolean (seq (get-in @observed [:coverage :limitations]))) => true)))))))

(fact "Full source failures and unauthorized identities never emit any envelope"
  (doseq [alter [#(update % :query dissoc :profileId)
                 #(assoc-in % [:query :accountId] 999)
                 #(assoc-in % [:transactions 26 :amount :currency] "EUR")
                 #(assoc-in % [:transactions 26 :date] "2000-01-01T00:00:00Z")]]
    (fixture/with-wise {}
      (fn [_]
        (with-statement alter
          (fn []
            (let [observed (atom []) failed (atom false)]
              (binding [wise/*dataset-observer* #(swap! observed conj %)]
                (try (query {"action" "transactions"})
                     (catch Exception _ (reset! failed true))))
              @failed => true
              @observed => []))))))
  (fixture/with-wise {:users ["OTHER"]}
    (fn [calls]
      (let [observed (atom []) failed (atom false)]
        (binding [wise/*dataset-observer* #(swap! observed conj %)]
          (try (query {"action" "balance"})
               (catch Exception _ (reset! failed true))))
        @failed => true
        @observed => []
        @calls => []))))

(fact "A host retention failure cannot turn a completed read into an API failure"
  (fixture/with-wise {}
    (fn [calls]
      (let [attempts (atom 0)
            r (binding [wise/*dataset-observer*
                        (fn [_] (swap! attempts inc) (throw (Exception. "retention refused")))]
                (query {"action" "transactions"}))]
        (:transaction-count r) => 2
        @attempts => 1
        (fixture/statement-urls @calls) => [fixture/statement-url]))))

(fact "Distinct same-currency balances keep request-local identities"
  (fixture/with-wise {:accounts [{:name "team" :group-id 42 :profile-id 11}]}
    (fn [_]
      (fixture/override-reply
        (fn [_ url options]
          (if (string/ends-with? url "/balances")
            (fixture/response
              [{:id 301 :groupId 42 :currency "USD" :amount {:value 10M :currency "USD"}}
               {:id 303 :groupId 42 :currency "USD" :amount {:value 20M :currency "USD"}}])
            (fixture/response
              {:query (fixture/statement-query options)
               :transactions [{:date (get-in options [:query-params :intervalEnd])
                               :type "CREDIT" :amount {:value 1M :currency "USD"}
                               :runningBalance {:value 10M :currency "USD"}}]})))
        (fn []
          (let [observed (atom nil)]
            (binding [wise/*dataset-observer* #(reset! observed %)]
              (query {"action" "transactions"}))
            (mapv #(get % "balance") (:rows @observed))
            => ["USD balance 1" "USD balance 2"]
            (:historical-balance-available? @observed) => true))))))

(fact "actual Wise statement command charts exact amount against fees through the ordinary adapter once"
  (fixture/with-wise {}
    (fn [calls]
      (with-statement identity
        (fn []
          (let [renders (atom []) turns (atom 0) retained (atom []) lookup datasets/lookup!
                t (assoc charts/toolset :providers [{:name "finance" :models ["chart"]}])]
            (with-redefs [ai/config (constantly {:providers [{:name "finance" :model "chart"
                                                            :url "https://finance.invalid/v1"}]})
                          mcp/config (constantly {:servers [] :toolsets [t]})
                          context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                          datasets/lookup! (fn [& args] (let [d (apply lookup args)] (swap! retained conj d) d))
                          http/post (fn [_ options]
                                      (let [messages (get-in options [:form-params :messages])]
                                        {:status 200 :body
                                         (if (= 1 (swap! turns inc))
                                           (charts/call "collect" "command" {"command" "wise transactions --days 14 --limit 20"})
                                           (let [d (some #(when (= "wise" (get % "source")) %)
                                                         (keep #(try (get-in (json/parse-string (:content %))
                                                                            ["structuredContent" "datasets" 0])
                                                                     (catch Exception _ nil)) messages))]
                                             (charts/call "chart" "chart"
                                                          {"dataset_id" (get d "dataset-id") "kind" "point"
                                                           "x" "amount" "y" "fees"})))}))
                          kroki/client-call (fn [operation request]
                                              (case operation
                                                "validate-render!" request
                                                "render!" (do (swap! renders conj request)
                                                              (public/reviewed {:result/value (:url charts/artifact)
                                                                                :result/data charts/artifact
                                                                                :result/public-data charts/artifact}))))]
              (charts/with-current-caller #(chat/run-command chat/request "ai chart amount against fees"))
              @turns => 2
              (count @renders) => 1
              (string/includes? (:source (first @renders) "") "0.10000001") => true
              (let [source (json/parse-string (:source (first @renders) "{}") true)]
                (get-in source [:data :values 0 :x]) => 0.10000001
                (get-in source [:data :values 0 :y]) => 0.01
                (count (get-in source [:data :values])) => 27)
              (get-in (first @retained) [:rows 0 "amount"]) => "0.10000001"
              (get-in (first @retained) [:rows 0 "fees"]) => "0.01"
              (fixture/statement-urls @calls) => [fixture/statement-url])))))))
