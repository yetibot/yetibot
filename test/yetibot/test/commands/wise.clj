(ns yetibot.test.commands.wise
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [midje.sweet :refer [fact facts =>]]
            [taoensso.timbre :as log]
            [yetibot.api.cloudflare :as transport]
            [yetibot.commands.wise]
            [yetibot.core.config :as config]
            [yetibot.test.support.ai-execution :as chat])
  (:import [java.io ByteArrayInputStream]
           [java.io InputStream]
           [java.time Instant ZoneOffset]
           [org.apache.http.client.methods HttpGet]))

(def settings
  {:accounts [{:name "team" :group-id 42}]
   :adapters ["slack"] :conversations ["workspace/C1"]})

(defn response [body]
  {:status 200 :body (ByteArrayInputStream.
                       (.getBytes (json/generate-string body) "UTF-8"))})

(defn statement-query [options]
  (merge {:profileId 11 :currency "USD"}
         (select-keys (:query-params options) [:intervalStart :intervalEnd])
         {:addStamp false :splitRefundFees true :timezone "UTC"
          :type "COMPACT"}))

(defn statement-urls [calls]
  (mapv first (filter #(string/includes? (first %) "balance-statements")
                      calls)))

(def statement-url
  (str "https://api.wise.com/2026Q4/profiles/11/"
       "balance-statements/301/statement.json"))

(defn reply [url options]
  (cond
    (string/ends-with? url "/profiles")
    (response [{:id 11 :type "PERSONAL"} {:id 22 :type "BUSINESS"}])
    (string/ends-with? url "/profiles/22/balances") (response [])
    (string/ends-with? url "/profiles/11/balances")
    (response [{:id 301 :groupId 42 :currency "USD"
                :amount {:value 12.34 :currency "USD"}}
               {:id 302 :groupId 99 :currency "USD"
                :amount {:value 999.00 :currency "USD"}
                :name "PRIVATE OTHER ACCOUNT"}])
    (string/includes? url "/balance-statements/301/")
    (response
      {:query (statement-query options)
       :accountHolder {:name "PRIVATE NAME"} :bankDetails {:iban "PRIVATE IBAN"}
       :transactions
       [{:date (str (.minusSeconds (Instant/now) 86400)) :type "DEBIT"
         :amount {:value -3.21 :currency "USD"}
         :totalFees {:value 0.04 :currency "USD"}
         :details {:description "Example Cloud" :accountNumber "PRIVATE"}}
        {:date (str (.minusSeconds (Instant/now) 172800)) :type "CREDIT"
         :amount {:value 10.00 :currency "USD"}
         :totalFees {:value 0 :currency "USD"}
         :details {:description "Group contribution"}}]})
    :else (throw (AssertionError. "Unexpected Wise URL"))))

(defn with-wise [overrides f]
  (let [file (java.io.File/createTempFile "wise-fixture-" ".key")
        original config/get-config
        calls (atom [])]
    (try
      (spit file "wise-fixture-token\n")
      (with-redefs
        [config/get-config
         (fn [spec path]
           (if (= path [:wise])
             {:value (merge settings {:key-file (.getAbsolutePath file)}
                            overrides)}
             (original spec path)))
         http/get (fn [url options]
                    (swap! calls conj [url options]) (reply url options))]
        (f calls))
      (finally (.delete file)))))

(defn run [text & [request]]
  (chat/run-command (or request chat/request) text))

(fact "Wise balance selects only the configured group"
  (with-wise {}
    (fn [calls]
      (let [r (run "wise balance")]
        (fact (:error r) => nil
              (get-in r [:data :account]) => "team"
              (get-in r [:data :balances])
              => [{:currency "USD" :available "12.34"}]
              (string/includes? (str r) "PRIVATE") => false
              (some #(string/includes? (first %) "balance-statements") @calls)
              => nil)))))

(fact "Real-shaped statements return projected, bounded transactions"
  (with-wise {}
    (fn [calls]
      (let [r (run "wise transactions --days 365 --limit 1")]
        (fact (:error r) => nil
              (get-in r [:data :transaction-count]) => 2
              (count (get-in r [:data :transactions])) => 1
              (get-in r [:data :transactions 0 :description])
              => "Example Cloud"
              (get-in r [:data :transactions 0 :amount]) => "-3.21"
              (get-in r [:data :has-more]) => true
              (string/includes? (str r) "PRIVATE") => false
              (string/includes? (str r) "wise-fixture-token") => false
              (statement-urls @calls) => [statement-url])))))

(fact "Real-shaped statements give exact summary totals over the full period"
  (with-wise {}
    (fn [_]
      (let [r (run "wise summary --days 365")
            total (get-in r [:data :totals 0])]
        (fact (:error r) => nil
              (:currency total) => "USD"
              (:credits total) => "10.00"
              (:debits total) => "3.21"
              (:net total) => "6.79"
              (:fees total) => "0.04"
              (:transaction-count total) => 2)))))

(fact "Oversized CLI input is rejected before option parsing"
  (let [parsed (atom false)]
    (with-redefs [cli/parse-opts (fn [& _] (reset! parsed true) {})]
      (fact
        (:result/error
          (yetibot.commands.wise/wise-cmd
            {:match (apply str (repeat 1001 "x"))}))
        => "Usage: wise balance|transactions|summary [options]."
        @parsed => false))))

(fact "Wise fails before transport for unconfigured accounts or invalid args"
  (with-wise {}
    (fn [calls]
      (doseq [text ["wise balance --account other"
                    "wise transactions --days 366"
                    "wise transactions --limit 0"
                    "wise transactions --profile-id 11"
                    "wise transfer 100 USD"
                    "wise balance --days 30"]]
        (fact (some? (:error (run text))) => true))
      (fact @calls => []))))

(fact "Wise requires trusted allowed chat provenance"
  (doseq [overrides [{:adapters ["discord"]}
                     {:conversations ["other/C1"]}
                     {:users ["OTHER"]}
                     {:conversations []}]]
    (with-wise overrides
      (fn [calls]
        (fact (some? (:error (run "wise balance"))) => true
              @calls => []))))
  (with-wise {}
    (fn [calls]
      (let [request (assoc-in chat/request [:chat-source :raw-event :user]
                              "FORGED")]
        (fact (some? (:error (run "wise balance" request))) => true
              @calls => [])))))

(fact "Wise never substitutes another account when a group is missing"
  (with-wise {:accounts [{:name "team" :group-id 999}]}
    (fn [calls]
      (fact (some? (:error (run "wise transactions"))) => true
            (some #(string/includes? (first %) "balance-statements") @calls)
            => nil))))

(fact "Wise supports a specifically configured balance under a pinned profile"
  (with-wise {:accounts [{:name "main" :profile-id 11 :balance-id 302}]}
    (fn [calls]
      (let [r (run "wise balance --account main")]
        (fact (:error r) => nil
              (get-in r [:data :balances])
              => [{:currency "USD" :available "999.00"}]
              (some #(string/ends-with? (first %) "/profiles") @calls)
              => nil)))))

(fact "Wise uses fixed-host bounded nonredirecting credentialed GETs"
  (with-wise {}
    (fn [calls]
      (run "wise transactions")
      (doseq [[url options] @calls]
        (fact (string/starts-with? url "https://api.wise.com/2026Q4/")
              => true
              (:redirect-strategy options) => :none
              (:as options) => :stream
              (get-in options [:headers "Authorization"])
              => "Bearer wise-fixture-token"
              ((:retry-handler options) nil 1 nil) => false)))))

(defn override-reply [f thunk]
  (let [original reply]
    (with-redefs [reply (fn [url options] (f original url options))]
      (thunk))))

(defn with-statement-query [alter-query thunk]
  (override-reply
    (fn [original url options]
      (if (string/includes? url "balance-statements")
        (response {:query (alter-query (statement-query options))
                   :transactions []})
        (original url options)))
    thunk))

(fact "Required statement identity fields fail closed without fallback"
  (doseq [alter-query
          (concat
            [(constantly nil) (constantly []) (constantly "PRIVATE QUERY")]
            (for [field [:profileId :currency :intervalStart :intervalEnd]]
              #(dissoc % field))
            (for [[field values]
                  {:profileId [nil 0 -11 22 11.5 "bad" "11.0" {}]
                   :currency [nil "EUR" "usd" 11 {}]
                   :intervalStart [nil "bad" 11 {}
                                   "2000-01-01T00:00:00Z"]
                   :intervalEnd [nil "bad" 11 {}
                                 "2000-01-01T00:00:00Z"]}
                  value values]
              #(assoc % field value)))]
    (doseq [action ["transactions" "summary"]]
      (with-wise {}
        (fn [calls]
          (with-statement-query alter-query
            (fn []
              (let [r (run (str "wise " action))]
                (fact (some? (:error r)) => true
                      (string/includes?
                        (str r)
                        "Wise statement did not match the configured balance.")
                      => true
                      (string/includes? (str r) "PRIVATE QUERY") => false
                      (:data r) => nil
                      (statement-urls @calls) => [statement-url])))))))))

(fact "Optional accountId must identify the selected balance when present"
  (doseq [value [nil 0 -301 302 "302" "bad" "301.0" 301.5 {}]]
    (with-wise {}
      (fn [calls]
        (with-statement-query #(assoc % :accountId value)
          (fn []
            (fact (some? (:error (run "wise transactions"))) => true
                  (statement-urls @calls) => [statement-url]))))))
  (doseq [value [301 "301"]]
    (with-wise {}
      (fn [calls]
        (with-statement-query #(assoc % :accountId value)
          (fn []
            (fact (:error (run "wise summary")) => nil
                  (statement-urls @calls) => [statement-url])))))))

(fact "Statement identity accepts digit profile IDs and equivalent instants"
  (with-wise {}
    (fn [calls]
      (with-statement-query
        (fn [query]
          (reduce (fn [q field]
                    (update q field
                            #(str (.atOffset (Instant/parse %)
                                             (ZoneOffset/ofHours -5)))))
                  (assoc query :profileId "11" :unknownFlag true)
                  [:intervalStart :intervalEnd]))
        (fn []
          (fact (:error (run "wise transactions")) => nil
                (:error (run "wise summary")) => nil
                (statement-urls @calls) => [statement-url statement-url]))))))

(fact "Credentials are redacted before descriptions are truncated"
  (with-wise {}
    (fn [_]
      (override-reply
        (fn [original url options]
          (if (string/includes? url "balance-statements")
            (response
              {:query (statement-query options)
               :transactions
               [{:date (str (.minusSeconds (Instant/now) 100))
                 :type "CREDIT" :amount {:value 1M :currency "USD"}
                 :details {:description
                           (str (apply str (repeat 195 "x"))
                                "wise-fixture-token")}}]})
            (original url options)))
        (fn []
          (let [r (run "wise transactions")]
            (fact (:error r) => nil
                  (string/includes? (str r) "wise-") => false)))))))

(fact "Duplicate balance metadata must not double count statements"
  (with-wise {}
    (fn [calls]
      (override-reply
        (fn [original url options]
          (if (string/ends-with? url "/profiles/11/balances")
            (response (vec (repeat 2 {:id 301 :groupId 42 :currency "USD"
                                     :amount {:value 1 :currency "USD"}})))
            (original url options)))
        (fn []
          (fact (some? (:error (run "wise summary"))) => true
                (some #(string/includes? (first %) "balance-statements")
                      @calls) => nil))))))

(fact "HTTP failure bodies and exceptions are never returned or logged"
  (doseq [status [301 302 401 403 429 500]]
    (with-wise {}
      (fn [_]
        (let [closed (atom false)
              logged (atom [])
              body (proxy [ByteArrayInputStream]
                          [(.getBytes "PRIVATE BANK wise-fixture-token")]
                     (close [] (reset! closed true)))]
          (with-redefs [http/get (fn [& _] {:status status :body body})]
            (binding [log/*config*
                      (update log/*config* :middleware conj
                              (fn [data] (swap! logged conj data) data))]
              (let [r (run "wise transactions")]
                (fact (some? (:error r)) => true
                      @closed => true
                      (string/includes? (str r @logged) "PRIVATE BANK")
                      => false
                      (string/includes? (str r @logged) "wise-fixture-token")
                      => false))))))))
  (with-wise {}
    (fn [_]
      (with-redefs [http/get
                    (fn [& _]
                      (throw (ex-info "PRIVATE BANK wise-fixture-token"
                                      {:headers "wise-fixture-token"})))]
        (let [r (run "wise balance")]
          (fact (some? (:error r)) => true
                (string/includes? (str r) "PRIVATE") => false
                (string/includes? (str r) "wise-fixture-token") => false))))))

(fact "Malformed and oversized streams are bounded and closed"
  (doseq [payload ["not JSON PRIVATE BANK wise-fixture-token"
                   (str "[" (string/join (repeat 2097152 " ")) "]")]]
    (with-wise {}
      (fn [_]
        (let [closed (atom false)
              stream (proxy [ByteArrayInputStream] [(.getBytes payload "UTF-8")]
                       (close [] (reset! closed true)))]
          (with-redefs [http/get (fn [& _] {:status 200 :body stream})]
            (let [r (run "wise balance")]
              (fact (some? (:error r)) => true
                    @closed => true
                    (string/includes? (str r) "PRIVATE BANK") => false))))))))

(fact "Unbounded responses stop at the byte cap and abort before closing"
  (with-wise {}
    (fn [_]
      (let [read-count (atom 0)
            events (atom [])
            stream (proxy [InputStream] []
                     (read
                       ([] 0)
                       ([buffer offset length]
                        (swap! read-count + length)
                        (java.util.Arrays/fill
                          ^bytes buffer (int offset) (int (+ offset length))
                          (byte 32))
                        length))
                     (close [] (swap! events conj :close)))
            request (proxy [HttpGet] []
                      (abort [] (swap! events conj :abort)))]
        (with-redefs [http/get (fn [& _] {:status 200 :body stream
                                        :request {:http-req request}})]
          (fact (some? (:error (run "wise balance"))) => true
                @read-count => 2097153
                @events => [:abort :close]))))))

(fact "Response body reads share the total request deadline"
  (with-wise {}
    (fn [_]
      (let [original transport/read-bytes
            deadlines (atom [])
            original-get http/get]
        (with-redefs
          [http/get (fn [& args]
                      (Thread/sleep 40)
                      (apply original-get args))
           transport/read-bytes
           (fn [stream maximum timeout]
             (when (= maximum 2097152) (swap! deadlines conj timeout))
             (original stream maximum timeout))]
          (fact (:error (run "wise balance")) => nil
                (every? #(< % 19980) @deadlines) => true))))))

(fact "Missing and invalid token files fail safely before any API call"
  (doseq [token [nil "" "short" "invalid token with spaces"
                 (string/join (repeat 4097 "x"))]]
    (let [file (java.io.File/createTempFile "wise-invalid-" ".key")]
      (try
        (if (nil? token) (.delete file) (spit file token))
        (with-wise {:key-file (.getAbsolutePath file)}
          (fn [calls]
            (let [r (run "wise balance")]
              (fact (some? (:error r)) => true
                    @calls => []
                    (string/includes? (str r) (.getAbsolutePath file))
                    => false))))
        (finally (.delete file))))))

(fact "An empty summary still gives the chat user a useful response"
  (with-wise {}
    (fn [_]
      (override-reply
        (fn [original url options]
          (if (string/includes? url "balance-statements")
            (response {:query (statement-query options) :transactions []})
            (original url options)))
        (fn []
          (let [r (run "wise summary")]
            (fact (:error r) => nil
                  (:value r) => ["team: 0 transactions in period."]
                  (get-in r [:data :totals]) => [])))))))

(fact "Group metadata bounds and statement identity fail closed"
  (doseq [body [nil {} [{:id "bad"}]
                (vec (repeat 11 {:id 11}))]]
    (with-wise {}
      (fn [calls]
        (override-reply
          (fn [_ _ _] (response body))
          (fn []
            (fact (some? (:error (run "wise transactions"))) => true
                  (count @calls) => 1))))))
  (doseq [body [{:query {:accountId 302} :transactions []}
                {:query {:currency "EUR"} :transactions []}
                {:transactions {}}
                {:transactions (vec (repeat 10001 {}))}]]
    (with-wise {}
      (fn [calls]
        (override-reply
          (fn [original url options]
            (if (string/includes? url "balance-statements")
              (response (update body :query
                                #(merge (statement-query options) %)))
              (original url options)))
          (fn []
            (fact (some? (:error (run "wise transactions"))) => true
                  (every? #(not (string/includes? (first %)
                                                 "balance-statements/302"))
                          @calls) => true)))))))

(fact "Bad rows are rejected after valid real-shaped statement identity"
  (doseq [bad-row [{:date "bad"} {:date "2000-01-01T00:00:00Z"}
                   {:type "OTHER"} {:details {:description {}}}
                   {:amount {:value "1.00" :currency "USD"}}
                   {:amount {:value 1M :currency "EUR"}}
                   {:totalFees {:value 0M :currency "EUR"}}]]
    (with-wise {}
      (fn [calls]
        (override-reply
          (fn [original url options]
            (if (string/includes? url "balance-statements")
              (response
                {:query (statement-query options)
                 :transactions
                 [(merge {:date (get-in options [:query-params :intervalEnd])
                          :type "CREDIT"
                          :amount {:value 1M :currency "USD"}}
                         bad-row)]})
              (original url options)))
          (fn []
            (let [r (run "wise transactions")]
              (fact (some? (:error r)) => true
                    (string/includes? (str r) "configured balance") => false
                    (:data r) => nil
                    (statement-urls @calls) => [statement-url]))))))))

(fact "Global newest-first limits and exact decimal totals span group balances"
  (with-wise {:accounts [{:name "team" :group-id 42 :profile-id 11}]}
    (fn [calls]
      (let [now (Instant/now)]
        (override-reply
          (fn [_ url options]
            (if (string/ends-with? url "/balances")
              (response
                [{:id 301 :groupId 42 :currency "USD"
                  :amount {:value 0.10000001M :currency "USD"}}
                 {:id 303 :groupId 42 :currency "USD"
                  :amount {:value 0.20000002M :currency "USD"}}
                 {:id 302 :groupId 99 :currency "USD"
                  :amount {:value 999M :currency "USD"}}])
              (let [first? (string/includes? url "/301/")]
                (response
                  {:query (statement-query options)
                   :transactions
                   [{:date (str (.minusSeconds now (if first? 100 10)))
                     :type "CREDIT"
                     :amount {:value (if first? 0.10000001M 0.20000002M)
                              :currency "USD"}
                     :details {:description (if first? "Older" "Newer")}}]}))))
          (fn []
            (let [r (run "wise transactions --limit 1")
                  s (run "wise summary --days 1")]
              (fact (:error r) => nil
                    (:error s) => nil
                    (get-in r [:data :transaction-count]) => 2
                    (get-in r [:data :transactions 0 :description]) => "Newer"
                    (get-in r [:data :transactions 0 :amount]) => "0.20000002"
                    (get-in s [:data :totals 0 :credits]) => "0.30000003"
                    (get-in s [:data :totals 0 :net]) => "0.30000003"
                    (statement-urls @calls)
                    => [statement-url
                        (string/replace statement-url "/301/" "/303/")
                        statement-url
                        (string/replace statement-url "/301/" "/303/")]
                    (some #(string/ends-with? (first %) "/profiles") @calls)
                    => nil))))))))

(fact "Group summary keeps currency totals separate"
  (with-wise {:accounts [{:name "team" :group-id 42 :profile-id 11}]}
    (fn [calls]
      (override-reply
        (fn [_ url options]
          (if (string/ends-with? url "/balances")
            (response
              [{:id 301 :groupId 42 :currency "USD"
                :amount {:value 1M :currency "USD"}}
               {:id 303 :groupId 42 :currency "EUR"
                :amount {:value 2M :currency "EUR"}}])
            (let [currency (get-in options [:query-params :currency])]
              (response
                {:query (assoc (statement-query options) :currency currency)
                 :transactions
                 [{:date (get-in options [:query-params :intervalEnd])
                   :type "CREDIT"
                   :amount {:value (if (= currency "USD") 0.1M 0.2M)
                            :currency currency}}]}))))
        (fn []
          (let [r (run "wise summary")]
            (fact (:error r) => nil
                  (get-in r [:data :totals])
                  => [{:currency "EUR" :transaction-count 1 :credits "0.20"
                       :debits "0.00" :net "0.20" :fees "0.00"}
                      {:currency "USD" :transaction-count 1 :credits "0.10"
                       :debits "0.00" :net "0.10" :fees "0.00"}]
                  (statement-urls @calls)
                  => [statement-url
                      (string/replace statement-url "/301/" "/303/")])))))))
