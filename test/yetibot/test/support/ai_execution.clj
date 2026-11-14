(ns yetibot.test.support.ai-execution
  (:require [yetibot.core.handler :as handler]
            [clojure.java.jdbc :as jdbc]
            [yetibot.core.db.util :as db]
            [yetibot.core.db.agent-run :as ledger]
            [yetibot.core.adapters.adapter :as adapters]
            [yetibot.core.adapters.slack :as slack]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.models.users :as users]
            [yetibot.core.chat :as chat]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.command :as command]
            [yetibot.util.ai-execution :as execution]))

(def request {:user {:id "U1" :username "alice"}
              :chat-source {:adapter :slack :uuid "workspace" :room "private"
                            :raw-event {:user "U1" :channel "C1"
                                        :ts "now" :thread_ts "root"}}})

(defn in-event [request text f]
  (let [body (str command/config-prefix text)
        field (if (= :slack (get-in request [:chat-source :adapter])) :text :content)
        chat-source (assoc-in (:chat-source request) [:raw-event field] body)]
    (execution/with-adapter-event
      (fn [cs user _ _ _] (f (assoc request :chat-source cs :user user)))
      chat-source (:user request) :message {:id "BOT"} {:body body})))

(defn run-command
  "Use the production adapter boundary and real parser/interpreter/command hooks.
   Only database-backed channel settings are stubbed."
  [request text]
  (in-event request text
    (fn [{:keys [chat-source user]}]
      (with-redefs [channel/settings-for-chat-source (constantly {})]
        (handler/handle-parsed-expr chat-source user {:id "BOT"}
                                    (parser/parser text))))))

(defn with-registered-caller
  "Explicit positive fixture: current real adapter protocol and users. The
   ordinary in-event helper deliberately does NOT authenticate/register DTOs."
  [request f]
  (let [source (:chat-source request)
        kind (:adapter source)
        adapter (case kind
                  :slack (slack/make-slack {:name (:uuid source)})
                  :discord (discord/make-discord {:name (:uuid source)}))
        bot {:id "BOT" :username "yetibot" :yetibot? true}]
    ;; Ready connection snapshot for component facts, not a gateway lifecycle
    ;; test. Discord's genuine start/pump lifecycle is qualified separately.
    (reset! (:connected? adapter) true)
    (reset! (:connection-last-active-timestamp adapter) (System/currentTimeMillis))
    (when (= kind :discord) (reset! (:yetibot-user adapter) bot))
    (with-redefs [adapters/adapters (atom (assoc @adapters/adapters (:uuid source) adapter))
                  users/users (atom (assoc @users/users
                                     {:adapter kind :id (get-in request [:user :id])} (:user request)
                                     {:adapter kind :id "BOT"} bot))]
      (binding [chat/*adapter* adapter
                chat/*target* (or (get-in source [:raw-event :channel])
                                  (get-in source [:raw-event :channel-id]) (:room source))]
        (f)))))

(defn- with-empty-recovery-read [f]
  (let [query jdbc/query observations (atom 0)
        statement [(str "SELECT * FROM " (db/qualified-table-name "agent_run")
                        " WHERE execution_kind=? AND execution_phase IN ('queued','ready','operation-in-flight')")
                   ledger/inherited-kind]]
    (with-redefs [jdbc/query (fn [database sql & [options :as more]]
                              (if (and (= statement sql) (= #{:identifiers} (set (keys options))))
                                (do (swap! observations inc) [])
                                (apply query database sql more)))]
      (let [result (f)]
        (assert (= 1 @observations) "Expected exactly one genuine ingress recovery observation")
        result))))

(defn in-registered-event [request text f]
  (with-registered-caller request #(with-empty-recovery-read (fn [] (in-event request text f)))))

(defn run-registered-command [request text]
  (with-registered-caller request #(with-empty-recovery-read (fn [] (run-command request text)))))

(defn configured-test-url!
  "Fixtures may use only the explicitly selected test URL, never a default DB."
  [config explicit-url]
  (when-not (and (string? explicit-url)
                 (re-find #"^postgres(?:ql)?://[^\s]+/[^/\s]+$" explicit-url)
                 (= explicit-url (:url config)))
    (throw (ex-info "Set an explicit YETIBOT_DB_URL for isolated test tables." {})))
  explicit-url)
