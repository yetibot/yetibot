(ns yetibot.api.wise
  "Read-only Wise data, narrowed by server-configured accounts and chat access."
  (:require [cheshire.core :as json]
            [cheshire.parse :as parse]
            [clj-http.client :as http]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [yetibot.api.cloudflare :as transport]
            [yetibot.core.config :as config]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.util.ai-execution :as execution])
  (:import [java.io File FileInputStream]
           [java.time Instant]))

(def endpoint "https://api.wise.com/2026Q4")

(defn- fail! [message]
  (throw (ex-info message {:ai/error true :wise/error true})))

(defn config [] (:value (config/get-config map? [:wise])))

(defn- strings? [xs]
  (and (vector? xs) (seq xs)
       (every? #(and (string? %) (not (string/blank? %))) xs)))

(defn- id [x]
  (try
    (let [n (if (and (string? x) (re-matches #"[0-9]{1,18}" x))
              (Long/parseLong x) x)]
      (when (and (integer? n) (<= 1 n Long/MAX_VALUE)) n))
    (catch Exception _ nil)))

(defn- account-name? [x]
  (and (string? x) (boolean (re-matches #"[a-zA-Z0-9_-]{1,32}" x))))

(defn- configuration! [raw]
  (when-not
    (and (map? raw) (string? (:key-file raw))
         (.isAbsolute (File. ^String (:key-file raw)))
         (vector? (:accounts raw)) (<= 1 (count (:accounts raw)) 10)
         (strings? (:adapters raw))
         (every? #{"slack" "discord"} (:adapters raw))
         (strings? (:conversations raw))
         (or (not (contains? raw :users)) (strings? (:users raw))))
    (fail! "Configure Wise key-file, accounts, adapters and conversations."))
  (let [accounts
        (mapv
          (fn [a]
            (when-not
              (and (map? a) (account-name? (:name a))
                   (every? #{:name :profile-id :group-id :balance-id} (keys a))
                   (not= (contains? a :group-id) (contains? a :balance-id))
                   (if (contains? a :group-id)
                     (id (:group-id a))
                     (and (id (:profile-id a)) (id (:balance-id a))))
                   (or (not (contains? a :profile-id)) (id (:profile-id a))))
              (fail! (str "Wise accounts must pin a group or a "
                          "profile/balance pair.")))
            (reduce #(if (contains? %1 %2) (update %1 %2 id) %1)
                    a [:profile-id :group-id :balance-id]))
          (:accounts raw))
        names (mapv :name accounts)
        default (or (:default-account raw) (first names))]
    (when-not (and (= (count names) (count (distinct names)))
                   (some #{default} names))
      (fail! "Wise account names and default-account must be valid."))
    (assoc raw :accounts accounts :default-account default)))

(defn authorize! [actor]
  (let [cfg (configuration! (config))]
    (when-not (and execution/*sensitive* actor
                   (= actor (policy/actor execution/*event*))
                   (some #{(:adapter actor)} (:adapters cfg))
                   (some #{(:conversation actor)
                           (str (:connection actor) "/*")}
                         (:conversations cfg))
                   (or (not (contains? cfg :users))
                       (some #{(:user actor)} (:users cfg))))
      (fail! "Wise is not enabled for this user or conversation."))
    cfg))

(defn available? [actor]
  (try (authorize! actor) true (catch Exception _ false)))

(defn arguments! [args]
  (when-not (map? args) (fail! "Invalid Wise arguments."))
  (let [action (get args "action")
        keys-allowed (case action
                       "balance" #{"action" "account"}
                       "transactions" #{"action" "account" "days" "limit"}
                       "summary" #{"action" "account" "days"}
                       nil)]
    (when-not
      (and (map? args) keys-allowed (every? keys-allowed (keys args))
           (or (not (contains? args "account"))
               (account-name? (get args "account")))
           (or (not (contains? args "days"))
               (and (integer? (get args "days"))
                    (<= 1 (get args "days") 365)))
           (or (not (contains? args "limit"))
               (and (integer? (get args "limit"))
                    (<= 1 (get args "limit") 20))))
      (fail! "Invalid Wise arguments. Use balance, transactions or summary."))
    args))

(defn- target! [cfg args]
  (or (some #(when (= (:name %) (get args "account" (:default-account cfg))) %)
            (:accounts cfg))
      (fail! "That Wise account is not configured.")))

(defn validate-call! [actor args]
  (arguments! args)
  (target! (authorize! actor) args)
  args)

(defn- credential! [cfg]
  (try
    (let [file (File. ^String (:key-file cfg))]
      (when-not (.isFile file) (fail! "Could not read the Wise token file."))
      (with-open [stream (FileInputStream. file)]
        (let [token (string/trim
                      (String. ^bytes (transport/read-bytes stream 4096 1000)
                               "UTF-8"))]
          (when-not (re-matches #"[A-Za-z0-9._~-]{16,4096}" token)
            (fail! "The Wise token file is empty or invalid."))
          token)))
    (catch Exception _ (fail! "Could not read the Wise token file."))))

(defn- remaining! [deadline]
  (let [ms (quot (- deadline (System/nanoTime)) 1000000)]
    (when-not (pos? ms) (fail! "Wise request exceeded its deadline."))
    ms))

(defn- get! [token deadline path query]
  (try
    (let [remaining (remaining! deadline)
          response
          (http/get (str endpoint path)
                    {:as :stream :throw-exceptions false :save-request true
                     :conn-timeout (min 3000 remaining)
                     :connection-request-timeout (min 3000 remaining)
                     :socket-timeout (min 5000 remaining)
                     :redirect-strategy :none
                     :retry-handler (fn [& _] false)
                     :headers {"Authorization" (str "Bearer " token)
                               "Accept" "application/json"}
                     :query-params query})]
      (transport/consume-response response
        (fn [stream]
          (when-not (= 200 (:status response))
            (fail! (case (:status response)
                     401 "Wise rejected the configured token (HTTP 401)."
                     403 (str "Wise denied access (HTTP 403). Statements may "
                              "require additional authentication or access.")
                     "Wise returned an unsuccessful response.")))
          (let [bytes (transport/read-bytes stream 2097152
                                            (remaining! deadline))
                body (binding [parse/*use-bigdecimals?* true]
                       (json/parse-string-strict
                         (String. ^bytes bytes "UTF-8") true))]
            (remaining! deadline)
            body))))
    (catch Exception e
      (if (:wise/error (ex-data e)) (throw e)
        (fail! "Wise request failed or returned an invalid response.")))))

(defn- list! [value maximum]
  (when-not (and (vector? value) (<= (count value) maximum))
    (fail! "Wise returned an invalid or oversized account/transaction list."))
  value)

(defn- balances! [target token deadline]
  (let [profiles (if-let [pid (:profile-id target)] [{:id pid}]
                  (list! (get! token deadline "/profiles" nil) 10))
        balances
        (mapcat
          (fn [profile]
            (let [pid (id (:id profile))]
              (when-not pid (fail! "Wise returned an invalid profile."))
              (map #(assoc % ::profile pid)
                   (list! (get! token deadline
                                (str "/profiles/" pid "/balances")
                                {:types "STANDARD,SAVINGS"}) 100)))) profiles)
        selected (filterv #(if-let [gid (:group-id target)]
                             (= gid (:groupId %))
                             (= (:balance-id target) (:id %))) balances)]
    (when-not (<= 1 (count selected) 10)
      (fail! "No accessible balances match the configured Wise account."))
    (when-not (= (count selected)
                 (count (distinct (map (juxt ::profile :id) selected))))
      (fail! "Wise returned duplicate balance metadata."))
    (doseq [b selected]
      (when-not (and (id (:id b)) (string? (:currency b))
                     (re-matches #"[A-Z]{3}" (:currency b)))
        (fail! "Wise returned an invalid balance.")))
    selected))

(defn- decimal! [value]
  (when-not (number? value) (fail! "Wise returned an invalid monetary amount."))
  (let [n (bigdec value)]
    (when-not (and (<= (.precision n) 24) (<= -8 (.scale n) 8))
      (fail! "Wise returned an invalid monetary amount."))
    n))

(defn- money [value]
  (let [n (decimal! value)]
    (.toPlainString (.setScale n (max 2 (.scale n))))))

(defn- amount! [amount currency]
  (when-not (= currency (:currency amount))
    (fail! "Wise returned an unexpected currency."))
  (money (:value amount)))

(defn- instant [value]
  (try
    (when (string? value) (Instant/parse value))
    (catch Exception _ nil)))

(defn- transactions! [b token deadline start end]
  ;; Wise need not echo accountId. Balance identity is bound to this fixed
  ;; official request path, built only from authorized selected metadata.
  (let [body (get! token deadline
                   (str "/profiles/" (::profile b) "/balance-statements/"
                        (:id b) "/statement.json")
                   {:currency (:currency b) :intervalStart (str start)
                    :intervalEnd (str end) :type "COMPACT"})
        query (:query body)]
    (when-not (and (map? query)
                   (= (::profile b) (id (:profileId query)))
                   (= (:currency b) (:currency query))
                   (= start (instant (:intervalStart query)))
                   (= end (instant (:intervalEnd query)))
                   (or (not (contains? query :accountId))
                       (= (id (:id b)) (id (:accountId query)))))
      (fail! "Wise statement did not match the configured balance."))
    (mapv
      (fn [tx]
        (let [date (Instant/parse (:date tx))
              raw-description (get-in tx [:details :description] "")
              description (when (string? raw-description)
                            (string/replace raw-description token
                                            "[redacted]"))]
          (when-not (and (not (.isBefore date start))
                         (not (.isAfter date end))
                         (#{"CREDIT" "DEBIT"} (:type tx))
                         (string? description))
            (fail! "Wise returned an invalid transaction."))
          {:date (str date) :type (:type tx) :currency (:currency b)
           :amount (amount! (:amount tx) (:currency b))
           :fees (amount! (or (:totalFees tx)
                              {:value 0 :currency (:currency b)}) (:currency b))
           :description (subs description 0 (min 200 (count description)))}))
      (list! (:transactions body) 10000))))

(defn- totals [transactions]
  (mapv
    (fn [[currency rows]]
      (let [amounts (map #(bigdec (:amount %)) rows)
            credits (reduce + 0M (filter pos? amounts))
            debits (- (reduce + 0M (filter neg? amounts)))]
        {:currency currency :transaction-count (count rows)
         :credits (money credits) :debits (money debits)
         :net (money (- credits debits))
         :fees (money (reduce + 0M (map #(bigdec (:fees %)) rows)))}))
    (sort-by key (group-by :currency transactions))))

(defn query! [actor args]
  (execution/sensitive!)
  (let [cfg (authorize! actor)
        args (arguments! args)
        target (target! cfg args)
        token (credential! cfg)
        deadline (+ (System/nanoTime) 20000000000)
        end (Instant/now)
        start (.minusSeconds end (* 86400 (get args "days" 30)))]
    (try
      (let [balances (balances! target token deadline)
            result {:account (:name target) :action (get args "action")
                    :as-of (str end)
                    :balances (mapv #(hash-map
                                       :currency (:currency %)
                                       :available (amount! (:amount %)
                                                           (:currency %)))
                                    balances)}
            result
            (if (= "balance" (get args "action")) result
              (let [rows (vec (mapcat
                                #(transactions! % token deadline start end)
                                balances))
                    result (assoc result :from (str start) :to (str end)
                                         :transaction-count (count rows))]
                (if (= "summary" (get args "action"))
                  (assoc result :totals (totals rows))
                  (assoc result
                         :transactions
                         (->> rows
                              (sort-by #(Instant/parse (:date %))
                                       #(compare %2 %1))
                              (take (get args "limit" 10)) vec)
                         :has-more (> (count rows) (get args "limit" 10))))))]
        (walk/postwalk #(if (string? %)
                         (string/replace % token "[redacted]") %) result))
      (catch Exception e
        (if (:wise/error (ex-data e)) (throw e)
          (fail! "Wise returned an invalid response."))))))
