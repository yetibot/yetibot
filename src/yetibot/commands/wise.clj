(ns yetibot.commands.wise
  (:require [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [yetibot.api.wise :as wise]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.models.ai-tool-policy :as policy]))

(defn- usage! []
  (throw (ex-info "Usage: wise balance|transactions|summary [options]."
                  {:wise/error true})))

(defn- arguments! [text]
  (when-not (and (string? text) (<= (count text) 1000)) (usage!))
  (let [{:keys [options arguments errors]}
        (cli/parse-opts
          (remove string/blank? (string/split (string/trim text) #"\s+"))
          [[nil "--account NAME" "Configured account name"]
           [nil "--days N" "Look back 1-365 days" :parse-fn #(Long/parseLong %)]
           [nil "--limit N" "Return 1-20 transactions"
            :parse-fn #(Long/parseLong %)]])]
    (when (or errors (> (count arguments) 1)) (usage!))
    (wise/arguments!
      (into {"action" (or (first arguments) "balance")}
            (map (fn [[k v]] [(name k) v]) options)))))

(defn render [result]
  (let [account (:account result)]
    (case (:action result)
      "balance"
      (mapv #(str account ": " (:available %) " " (:currency %))
            (:balances result))
      "transactions"
      (into [(str account ": " (:transaction-count result)
                  " transactions in period; showing "
                  (count (:transactions result)) ".")]
            (map #(str (:date %) " " (:amount %) " " (:currency %) " "
                       (:description %)) (:transactions result)))
      "summary"
      (if (seq (:totals result))
        (mapv #(str account " " (:currency %) ": "
                    (:transaction-count %) " transactions; in " (:credits %)
                    ", out " (:debits %) ", net " (:net %) ", fees " (:fees %))
              (:totals result))
        [(str account ": 0 transactions in period.")]))))

(defn wise-cmd
  "wise balance [--account name] # balance of a configured Wise group/account
   wise transactions [--days 30] [--limit 10] [--account name]
   wise summary [--days 30] [--account name] # credits/debits/net/fees
   # Read-only. Server config selects accounts and allowed conversations.
   # Days: 1-365; transaction limit: 1-20. No transfers or payments.
   # Full projected results remain available through data."
  {:yb/cat #{:financial}}
  [{:keys [match] :as args}]
  (try
    (let [text (if (string? match) match (or (second match) ""))
          result (wise/query! (policy/actor args) (arguments! text))]
      {:result/value (render result) :result/data result})
    (catch Exception e
      {:result/error (if (:wise/error (ex-data e)) (.getMessage e)
                        "Wise read failed. Check configuration and access.")})))

(cmd-hook #"wise" #"(?s)(.*)" wise-cmd)
