(ns yetibot.test.models.ai-agent-runs
  (:require [midje.sweet :refer [fact facts => throws]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [yetibot.core.db :as database]
            [yetibot.core.db.util :as db]
            [yetibot.core.db.agent-run :as ledger]
            [yetibot.core.db.cron :as cron]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-agent-runs :as runs]
            [yetibot.util.ai-execution :as execution]
            [yetibot.test.support.ai-execution :as support]))

(defn with-store [f]
  (let [config (db/config)
        prefix (str "app_u3_" (System/nanoTime) "_")]
    (support/configured-test-url! config (System/getenv "YETIBOT_DB_URL"))
    (with-redefs [db/config (constantly (assoc config :table {:prefix prefix}))]
      (try
        (doseq [schema [ledger/schema cron/schema]]
          (database/idempotent-create-table! (:schema/table schema) (:schema/specs schema)))
        (f (:url config))
        (finally
          (doseq [table ["agent_run" "cron"]]
            (jdbc/db-do-commands (:url config)
              (str "DROP TABLE IF EXISTS " (db/qualified-table-name table)))))))))

(def state {:job-id "job" :family-id "family" :parent-id nil
            :authority {:source {:adapter :slack :uuid "workspace" :room "private"
                                 :raw-event {:user "U1" :channel "C1" :ts "now"}}
                        :user {:id "U1" :username "alice"}
                        :yetibot-user {:id "BOT"}
                        :actor {:adapter "slack" :connection "workspace" :user "U1"
                                :conversation "workspace/C1" :thread "C1"}
                        :tools #{["yetibot" "command"]}
                        :toolset-name nil :toolset-policy nil
                        :planner {:name "local" :model "chat" :signature "signature"}}
            :generation-cap 32768 :phase :queued
            :loop {:messages [{:role "user" :content "synthetic prompt"}]}})

(defn create! [url id limit]
  (jdbc/with-db-transaction [tx url]
    (ledger/create-inherited! tx "agent_run"
      {:run-id id :request "Inherited work queued."
       :execution-state (runs/encode (assoc state :job-id id) {})}
      {:limit limit :generation-cap (:generation-cap state)})))

(defn claim! [url row]
  (runs/claim! url "agent_run" (:id row) (:state-revision row) {}))

(defn read! [url row] (ledger/read-row url "agent_run" (:id row)))

(fact "strict versioned checkpoints preserve data but not reviewed metadata"
  (let [marked (public/reviewed {:result/public-data {:safe true}})
        original (assoc state :loop {:messages [] :seen #{"call"}
                                    :body marked})
        restored (runs/decode (runs/encode original {}) {})]
    restored => original
    (public/projection (get-in restored [:loop :body])) => nil
    (runs/decode (json/generate-string {"version" 2 "checkpoint" nil}) {})
    => (throws Exception)
    (doseq [text ["{} garbage" "{} {}" "{\"version\":1,}" "[]" "null"]]
      (runs/decode text {}) => (throws Exception))
    (doseq [value [(assoc state :generation-cap 262145)
                   (assoc state :loop {:private "SYNTHETIC_SECRET"})
                   (assoc state :loop {:token "hidden"})
                   (assoc state :loop {:messages (apply str (repeat 1100000 "é"))})
                   (assoc-in state [:authority :direct-input] "fake")]]
      (runs/encode value {:secrets ["SYNTHETIC_SECRET"]}) => (throws Exception))))

(fact "20 minus 3 transfers 17 as reserved, job admissions begin at zero"
  (with-store
    (fn [url]
      (let [parent (execution/family 20 32768)
            _ (execution/with-request* parent
                #(dotimes [_ 3]
                   (execution/finish-operation!
                     (execution/admit-operation! :tool) {:state :definitive})))
            row (runs/transfer-live! url parent "agent_run"
                  {:run-id "job" :request "queued"} state {})
            session (claim! url row)]
        (select-keys @(:budget parent) [:limit :used :reserved])
        => {:limit 20 :used 3 :reserved 17}
        (execution/with-request* parent execution/remaining) => 0
        (:execution-limit row) => 17
        (:execution-used row) => 0
        (doseq [kind [:inference :tool :inference]]
          (let [handle (runs/admit! session kind nil)]
            (runs/finish! session handle {:state :definitive})
            (runs/checkpoint! session @(:state session) nil)))
        (select-keys @(:budget session) [:limit :used :reserved])
        => {:limit 17 :used 3 :reserved 0}
        (:generation-cap (read! url row)) => 32768
        (execution/close-family! parent)
        (:reserved @(:budget parent)) => 17))))

(fact "live concurrent launches and failed commit seal allocation without refund"
  (with-store
    (fn [url]
      (let [parent (execution/family 17 91)
            launches (mapv deref
                       (mapv (fn [id]
                               (future
                                 (runs/transfer-live! url parent "agent_run"
                                   {:run-id id :request "queued"}
                                   (assoc state :job-id id :generation-cap 91) {}))) ["a" "b"]))]
        (sort (map :execution-limit launches)) => [0 17]
        (:reserved @(:budget parent)) => 17)
      (let [parent (execution/family 20 123)]
        (runs/transfer-live! url parent "agent_run"
          {:run-id "invalid"} (assoc state :generation-cap 123) {}) => (throws Exception)
        (select-keys @(:budget parent) [:closed? :reserved :uncertain-job-id])
        => {:closed? true :reserved 20 :uncertain-job-id "job"}
        (jdbc/query url [(str "SELECT count(*) n FROM " (db/qualified-table-name "agent_run")
                                             " WHERE run_id='invalid'")]) => [{:n 0}]))))

(fact "durable nested and concurrent transfers conserve one original allocation"
  (with-store
    (fn [url]
      (let [parent (create! url "parent" 17)
            owner {:table "agent_run" :id (:id parent)}
            launches (mapv deref
                       (mapv (fn [id]
                               (future
                                 (jdbc/with-db-transaction [tx url]
                                   (runs/transfer! tx owner "agent_run"
                                     {:run-id id :request "queued"}
                                     (assoc state :job-id id) {})))) ["a" "b"]))
            child (first (filter #(pos? (:execution-limit %)) launches))
            grandchild (jdbc/with-db-transaction [tx url]
                         (runs/transfer! tx {:table "agent_run" :id (:id child)}
                           "agent_run" {:run-id "grandchild" :request "queued"}
                           (assoc state :job-id "grandchild") {}))]
        (sort (map :execution-limit launches)) => [0 17]
        (:execution-reserved (read! url parent)) => 17
        (:execution-reserved (read! url child)) => 17
        (:execution-limit grandchild) => 17
        (try
          (jdbc/with-db-transaction [tx url]
            (runs/transfer! tx {:table "agent_run" :id (:id grandchild)}
              "agent_run" {:run-id "rolled-back" :request "queued"} state {})
            (throw (Exception. "rollback")))
          (catch Exception _ nil))
        (:execution-reserved (read! url grandchild)) => 0
        (jdbc/query url [(str "SELECT count(*) n FROM " (db/qualified-table-name "agent_run")
                             " WHERE run_id='rolled-back'")]) => [{:n 0}]))))

(fact "nested completion cannot release an enclosing command or resurrect a fence"
  (with-store
    (fn [url]
      (let [row (create! url "nested" 6) session (claim! url row)
            command (runs/admit! session :command nil)
            inference (runs/admit! session :inference command)
            _ (runs/finish! session inference {:state :definitive})
            tool (runs/admit! session :tool command)
            nested (runs/admit! session :inference tool)]
        (mapv :ordinal [command inference tool nested]) => [1 2 3 4]
        (mapv :parent-ordinal [command inference tool nested]) => [nil 1 1 3]
        (runs/finish! session nested {:state :definitive})
        (runs/finish! session tool {:state :definitive})
        (runs/checkpoint! session @(:state session) nil) => (throws Exception)
        (:execution-phase (read! url row)) => "operation-in-flight"
        ;; Crash after all inner completions, before the outer reply commit.
        (claim! url (read! url row)) => {:status :unknown}
        (runs/finish! session command {:state :definitive}) => (throws Exception)
        (runs/admit! session :tool command) => (throws Exception)
        (:execution-used (read! url row)) => 4))))

(fact "a safe outer reply commits atomically; oversize rollback keeps it unknown"
  (with-store
    (fn [url]
      (let [row (create! url "safe" 3) session (claim! url row)
            tool (runs/admit! session :tool nil)]
        (runs/checkpoint! session
          (assoc @(:state session) :loop {:messages [{:role "user" :content (apply str (repeat 2100000 "x"))}]}) tool)
        => (throws Exception)
        (:execution-phase (read! url row)) => "operation-in-flight"
        (runs/checkpoint! session (assoc @(:state session) :loop {:messages []}) tool)
        (:execution-phase (read! url row)) => "ready"
        (let [resumed (claim! url (read! url row))]
          (:status resumed) => :claimed
          (:used @(:budget resumed)) => 1
          (runs/admit! session :inference nil) => (throws Exception))))))

(fact "last-unit and unknown subordinate failures never refund or unseal ancestor"
  (with-store
    (fn [url]
      (let [row (create! url "last" 2) session (claim! url row)
            outer (runs/admit! session :tool nil)
            inner (runs/admit! session :inference outer)]
        (runs/finish! session inner {:state :unknown})
        (runs/finish! session outer {:state :definitive}) => (throws Exception)
        (runs/admit! session :tool outer) => (throws Exception)
        (:execution-used (read! url row)) => 2
        (:execution-phase (read! url row)) => "unknown")
      (let [row (create! url "exhausted-safe" 1) session (claim! url row)
            outer (runs/admit! session :tool nil)]
        (runs/admit! session :inference outer) => (throws Exception)
        (runs/checkpoint! session (assoc @(:state session) :loop {:messages [{:role "assistant" :content "exhausted"}]}) outer)
        (:execution-phase (read! url row)) => "ready"
        (:execution-used (read! url row)) => 1))))

(fact "delivery has a fenced CAS, no extra allowance, and restart never resends"
  (with-store
    (fn [url]
      (let [row (create! url "delivery" 1) session (claim! url row)
            operation (runs/admit! session :inference nil)]
        (runs/finish! session operation {:state :definitive})
        (runs/save-result! session {:result/value "saved answer"} :exhausted)
        (let [other (assoc session :row (atom @(:row session)) :budget (atom @(:budget session)))
              winners (mapv deref
                        (mapv #(future (try (runs/begin-delivery! %)
                                             (catch Exception _ :denied))) [session other]))]
          (count (filter map? winners)) => 1
          (count (filter #{:denied} winners)) => 1)
        (:execution-used (read! url row)) => 1
        (runs/admit! session :tool nil) => (throws Exception)
        (claim! url (read! url row)) => {:status :unknown}
        (:execution-phase (read! url row)) => "terminal"
        (:execution-state (read! url row)) => nil
        (runs/finish-delivery! session) => (throws Exception)))))

(fact "cron transfers use the actual cron ledger and caller transaction, no fresh tick grant"
  (with-store
    (fn [url]
      (let [parent (create! url "cron-parent" 5)
            grant (-> state (dissoc :loop) (assoc :scheduled? true))
            cron-row (jdbc/with-db-transaction [tx url]
                       (runs/transfer! tx {:table "agent_run" :id (:id parent)} "cron"
                         {:schedule "* * * * *" :cmd "echo synthetic"
                          :execution-origin ledger/inherited-kind} grant {}))
            session (runs/claim! url "cron" (:id cron-row) 0 {})
            tick (runs/admit! session :tick nil)
            inference (runs/admit! session :inference tick)]
        (runs/finish! session inference {:state :definitive})
        (runs/checkpoint! session grant tick)
        (:execution-used (ledger/read-row url "cron" (:id cron-row))) => 2
        (:execution-reserved (read! url parent)) => 5
        (let [fresh (ledger/read-row url "cron" (:id cron-row))
              next-tick (runs/claim! url "cron" (:id fresh) (:state-revision fresh) {})
              tick (runs/admit! next-tick :tick nil)
              child (jdbc/with-db-transaction [tx url]
                      (runs/transfer! tx (:owner next-tick) "agent_run"
                        {:run-id "cron-child" :request "queued"} state {}))]
          (:execution-limit child) => 2
          (:execution-used (ledger/read-row url "cron" (:id cron-row))) => 3
          (:execution-reserved (ledger/read-row url "cron" (:id cron-row))) => 2
          (runs/admit! next-tick :inference tick) => (throws Exception))
        (set (keys (runs/cron-grant grant)))
        => #{:job-id :family-id :parent-id :authority :generation-cap :scheduled?}))))

(fact "concurrent last-unit admissions and stale checkpoint revisions fail before effects"
  (with-store
    (fn [url]
      (let [row (create! url "race" 1) session (claim! url row)
            copy (assoc session :row (atom @(:row session)) :budget (atom @(:budget session)))
            attempts (mapv deref
                       (mapv #(future (try (runs/admit! % :inference nil)
                                            (catch Exception _ :denied))) [session copy]))]
        (count (filter map? attempts)) => 1
        (count (filter #{:denied} attempts)) => 1
        (:execution-used (read! url row)) => 1))))

(fact "private delivery phase rejects late child transfers and allowance changes"
  (with-store
    (fn [url]
      (let [row (create! url "immutable" 4) session (claim! url row)]
        (runs/checkpoint! session (assoc-in @(:state session) [:generation-cap] 262144) nil)
        => (throws Exception)
        (runs/checkpoint! session (assoc-in @(:state session) [:authority :tools]
                                          #{["yetibot" "command"] ["yetibot" "chart"]}) nil)
        => (throws Exception)
        (runs/save-result! session {:result/value "saved"} :completed)
        (runs/begin-delivery! session)
        (jdbc/with-db-transaction [tx url]
          (runs/transfer! tx (:owner session) "agent_run"
                          {:run-id "late-child" :request "queued"} state {}))
        => (throws Exception)
        (runs/finish-delivery! session)
        (claim! url (read! url row)) => {:status :terminal}
        (:execution-reserved (read! url row)) => 0))))

(fact "lost commit acknowledgement seals live parent and recovers only the committed identity"
  (with-store
    (fn [url]
      (let [parent (execution/family 17 32768)
            transaction jdbc/db-transaction* attempts (atom 0)]
        (with-redefs [jdbc/db-transaction* (fn [& args]
                                           (let [result (apply transaction args)]
                                             (when (and (= 2 (count args))
                                                        (not (:connection (first args))))
                                               (swap! attempts inc)
                                               (throw (Exception. "scripted lost commit acknowledgement")))
                                             result))]
          (runs/transfer-live! url parent "agent_run" {:run-id "known-id" :request "queued"}
                              (assoc state :job-id "known-id") {}) => (throws Exception))
        @attempts => 1
        (select-keys @(:budget parent) [:used :reserved :closed? :uncertain-job-id])
        => {:used 0 :reserved 17 :closed? true :uncertain-job-id "known-id"}
        (let [rows (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run")
                                         " WHERE run_id='known-id'")])
              row (ledger/read-row url "agent_run" (:id (first rows)))]
          (count rows) => 1
          (:execution-limit row) => 17
          (:status (claim! url row)) => :claimed)))))

(fact "cron sticky updates preserve finite counters, fences and unknown effects"
  (with-store
    (fn [url]
      (let [parent (create! url "sticky-parent" 4)
            grant (runs/cron-state state)
            row (jdbc/with-db-transaction [tx url]
                  (runs/transfer! tx {:table "agent_run" :id (:id parent)} "cron"
                    {:schedule "* * * * *" :cmd "echo synthetic"
                     :execution-origin ledger/inherited-kind} grant {}))
            session (runs/claim! url "cron" (:id row) 0 {})
            tick (runs/admit! session :tick nil)]
        (runs/finish! session tick {:state :unknown})
        (jdbc/with-db-transaction [tx url]
          (let [attrs (cron/inherit-attrs! tx {:table "agent_run" :id (:id parent)}
                        (ledger/read-row tx "cron" (:id row))
                        {:schedule "*/2 * * * *" :cmd "echo edited"
                         :execution-limit 128 :execution-used 0 :execution-reserved 0
                         :execution-phase "queued"
                         :execution-state (runs/encode grant {})})]
            (jdbc/update! tx (db/qualified-table-name "cron") attrs ["id=?" (:id row)]
                          {:entities cuerdas.core/snake})))
        (let [updated (ledger/read-row url "cron" (:id row))]
          (:execution-limit updated) => 4
          (:execution-used updated) => 1
          (:execution-phase updated) => "unknown"
          (:execution-reserved (read! url parent)) => 4
          (runs/claim! url "cron" (:id row) (:state-revision updated) {}) => {:status :unknown}))))
)

(fact "normal and delivery-interrupted cleanup retain the same factual work summary"
  (with-store
    (fn [url]
      (doseq [[outcome summary] [[:completed "Inherited result produced; delivery unconfirmed."]
                                 [:exhausted "Inherited allowance exhausted; delivery unconfirmed."]
                                 [:partial "Inherited work partial; delivery unconfirmed."]
                                 [:queued "Inherited child work queued; delivery unconfirmed."]]
              restart? [false true]]
        (let [row (create! url (str (name outcome) restart?) 2)
              session (claim! url row)]
          (runs/save-result! session {:result/value "saved host result"} outcome)
          (runs/begin-delivery! session)
          (if restart?
            (claim! url (read! url row))
            (runs/finish-delivery! session))
          (select-keys (read! url row) [:request :execution-state :execution-phase
                                       :execution-used :execution-reserved])
          => {:request summary :execution-state nil :execution-phase "terminal"
              :execution-used 0 :execution-reserved 0})))))

(fact "terminal summary update failure rolls back cleanup and never modifies legacy prompts"
  (with-store
    (fn [url]
      (let [row (create! url "cleanup-failure" 2) session (claim! url row)
            update jdbc/update!]
        (runs/save-result! session {:result/value "saved"} :completed)
        (runs/begin-delivery! session)
        (with-redefs [jdbc/update! (fn [tx table attrs & args]
                                    (if (contains? attrs :request) [0]
                                      (apply update tx table attrs args)))]
          (runs/finish-delivery! session) => (throws Exception))
        (:execution-phase (read! url row)) => "ready"
        (:phase (runs/decode (:execution-state (read! url row)) {})) => :delivery-started
        (claim! url (read! url row)) => {:status :unknown}
        (:request (read! url row)) => "Inherited result produced; delivery unconfirmed.")
      (let [legacy (first (jdbc/insert! url (db/qualified-table-name "agent_run")
                                      {:run_id "legacy-untouched" :request "original legacy prompt"}))]
        (runs/claim! url "agent_run" (:id legacy) 0 {}) => (throws Exception)
        (:request (read! url legacy)) => "original legacy prompt"
        (:execution-kind (read! url legacy)) => nil))))

(fact "codec preserves exact decimal scalars and bounds import before materialization"
  (let [original (assoc state :loop {:messages [] :accounting {:usd 0.12345678901234567890M}})
        restored (runs/decode (runs/encode original {}) {})]
    (get-in restored [:loop :accounting :usd]) => 0.12345678901234567890M
    (str (get-in restored [:loop :accounting :usd])) => "0.12345678901234567890"
    (runs/decode (str (apply str (repeat 300 "[")) "0" (apply str (repeat 300 "]"))) {})
    => (throws Exception)
    (runs/decode "{\"version\":2,\"version\":1,\"checkpoint\":null}" {}) => (throws Exception)))

(fact "explicit isolated test URLs are portable across CI and owned local ports"
  (doseq [url ["postgresql://test@localhost:5432/test"
               "postgresql://test@127.0.0.1:55446/test"]]
    (support/configured-test-url! {:url url} url) => url)
  (support/configured-test-url! {:url "postgresql://localhost/production"} nil)
  => (throws Exception)
  (support/configured-test-url! {:url "postgresql://localhost/production"}
                                "postgresql://localhost/test") => (throws Exception))

(fact "cron delivery intent preserves its in-flight tick and cannot become a safe ready checkpoint"
  (with-store
    (fn [url]
      (let [request (execution/family 3 91)
            state (runs/cron-state (assoc state :generation-cap 91))
            row (runs/transfer-live! url request "cron"
                  {:schedule "0 0 8 * * * *" :cmd "echo bounded"
                   :execution-origin ledger/inherited-kind} state {})
            claim (jdbc/with-db-transaction [tx url] (cron/claim-tick! tx (:id row)))
            session (runs/claimed-cron-session url claim {})
            operation (:operation claim)]
        (runs/begin-cron-delivery! session (update-in operation [:owner :fence] inc)) => (throws Exception)
        (:phase (runs/decode (:execution-state (ledger/read-row url "cron" (:id row))) {})) => :queued
        (runs/begin-cron-delivery! session operation)
        (let [pending (ledger/read-row url "cron" (:id row))]
          (:execution-phase pending) => "operation-in-flight"
          (:phase (runs/decode (:execution-state pending) {})) => :cron-delivery-started
          (mapv :state (json/parse-string (:operation-receipts pending) true)) => ["intent"]
          (select-keys pending [:execution-used :execution-reserved])
          => {:execution-used 1 :execution-reserved 0})
        (runs/checkpoint! session @(:state session) operation) => (throws Exception)
        (runs/finish! session operation {:state :unknown})
        (jdbc/with-db-transaction [tx url] (cron/claim-tick! tx (:id row))) => {:status :unknown}
        (:execution-used (ledger/read-row url "cron" (:id row))) => 1))))
