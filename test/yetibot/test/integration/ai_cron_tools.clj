(ns yetibot.test.integration.ai-cron-tools
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.db.util :as db]
            [yetibot.core.models.admin :as admin]
            [yetibot.models.ai-cron-tools :as cron]
            [yetibot.models.ai-native-tools :as native]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.test.support.ai-execution :as execution]))

(def toolset {:name "cron-test" :kind "search" :servers []
              :native-tools ["cron"] :users ["U1" "U2"] :adapters ["slack"]
              :providers [{:name "fake" :models ["fake"]}]})
(def config {:toolset toolset :secrets ["known-private-token"]})
(def schedule "0 0 8 * * * *")

(defn call [args & [request cfg]]
  (execution/in-event (or request execution/request) "ai manage my cron schedules"
    (fn [event] (cron/call! (or cfg config) "cron" args (:chat-source event)))))

(defn with-store [f]
  (let [cfg (db/config)
        table (str "test_ai_cron_" (System/nanoTime) "_cron")
        runtime (atom [])]
    (jdbc/db-do-commands (:url cfg)
      (str "CREATE TABLE " table " (id SERIAL PRIMARY KEY,"
           "chat_source_adapter TEXT,chat_source_room TEXT,user_id TEXT,"
           "schedule TEXT NOT NULL,cmd TEXT NOT NULL)"))
    (try
      (with-redefs [db/config (constantly
                               (assoc cfg :table
                                      {:prefix (subs table 0 (- (count table) 4))}))
                    ai/config (constantly {})
                    admin/user-is-admin? (constantly false)
                    cron/runtime! (fn [action row]
                                    (swap! runtime conj [action row]))]
        (f runtime table (:url cfg)))
      (finally (jdbc/db-do-commands (:url cfg) (str "DROP TABLE " table))))))

(fact "native integration cannot initialize a stopped/unloaded scheduler"
  (let [find-ns clojure.core/find-ns]
    (with-redefs [clojure.core/requiring-resolve
                  (fn [& _] (throw (AssertionError. "No scheduler startup")))
                  clojure.core/find-ns
                  (fn [name]
                    (when-not (= name 'yetibot.core.commands.cron)
                      (find-ns name)))]
      (cron/available-tools) => ["cron"]
      (try (#'cron/runtime! :save {:id 1}) false
           (catch Exception _ true)) => true)))

(fact "cron is a structured management capability, not command dispatch"
  (get-in (native/definition "cron") [:inputSchema :type]) => "object"
  (get-in (native/definition "cron") [:inputSchema :additionalProperties])
  => false)

(fact "actual persisted CRUD preserves scope, ownership, versions and idempotency"
  (with-store
    (fn [runtime _ _]
      (let [created (call {"action" "create" "schedule" schedule
                           "command" "temps"})
            item (get-in created [:structuredContent :item])
            id (:id item) version (:version item)]
        (get-in created [:structuredContent :created]) => true
        (:command item) => "temps"
        (:can_modify item) => true
        (count version) => 64
        (mapv first @runtime) => [:save]
        (get-in (call {"action" "create" "schedule" schedule "command" "temps"})
                [:structuredContent :created]) => false
        (count @runtime) => 1
        (get-in (call {}) [:structuredContent :items]) => [item]
        (get-in (call {"action" "get" "id" id}) [:structuredContent :item])
        => item
        (let [other (-> execution/request
                        (assoc-in [:chat-source :room] "other")
                        (assoc-in [:chat-source :raw-event :channel] "C2"))]
          (get-in (call {"action" "get" "id" id} other)
                  [:structuredContent :found]) => false
          (:isError (call {"action" "delete" "id" id "version" version} other))
          => true)
        (let [other-user (-> execution/request
                            (assoc-in [:user :id] "U2")
                            (assoc-in [:chat-source :raw-event :user] "U2"))]
          (get-in (call {"action" "get" "id" id} other-user)
                  [:structuredContent :item :can_modify]) => false
          (:isError (call {"action" "delete" "id" id "version" version}
                         other-user)) => true)
        (count @runtime) => 1
        (let [updated (get-in (call {"action" "update" "id" id "version" version
                                     "command" "weather edmonton"})
                              [:structuredContent :item])]
          (:command updated) => "weather edmonton"
          (:schedule updated) => schedule
          (= version (:version updated)) => false
          (:isError (call {"action" "delete" "id" id "version" version}))
          => true
          (mapv first @runtime) => [:save :save]
          (get-in (call {"action" "delete" "id" id
                         "version" (:version updated)})
                  [:structuredContent :deleted]) => true
          (mapv first @runtime) => [:save :save :delete]
          (get-in (call {}) [:structuredContent :items]) => [])))))

(fact "bad schedules and escalation arguments fail before persistence/runtime"
  (with-store
    (fn [runtime _ _]
      (doseq [args [{"action" "stop"} {"action" "start"}
                    {"action" "list" "channel" "other"}
                    {"action" "create" "schedule" "* * * * *" "command" "temps"}
                    {"action" "create" "schedule" "0 99 8 * * * *" "command" "temps"}
                    {"action" "create" "schedule" "/0 0 8 * * * *" "command" "temps"}
                    {"action" "create" "schedule" "*/5 0 8 * * * *" "command" "temps"}
                    {"action" "create" "schedule" schedule "command" "!temps"}
                    {"action" "create" "schedule" schedule "command" "temps\ncron stop"}
                    {"action" "update" "id" 1 "command" "temps"}
                    {"action" "create" "schedule" schedule
                     "command" "echo password=PRIVATE-NEW"}]]
        (fact (:isError (call args)) => true))
      (:isError (cron/call! config "cron" {} {})) => true
      (:isError (call {} nil {:toolset (assoc toolset :users ["other"])}))
      => true
      @runtime => []
      (get-in (call {}) [:structuredContent :items]) => [])))

(fact "bounded pagination and redaction do not expose ownership or credentials"
  (with-store
    (fn [_ table url]
      (doseq [n (range 3)]
        (jdbc/insert! url table
                      {:chat_source_adapter (pr-str "workspace")
                       :chat_source_room "private" :user_id "PRIVATE-OWNER"
                       :schedule schedule
                       :cmd (if (zero? n) "echo password=PRIVATE-PASSWORD"
                                (str "echo " (apply str (repeat 1000 "x"))))}))
      (let [first-page (call {"limit" 1})
            next-id (get-in first-page [:structuredContent :next_after_id])
            second-page (call {"after_id" next-id "limit" 1})]
        (get-in first-page [:structuredContent :has_more]) => true
        (get-in first-page [:structuredContent :items 0 :command]) => "[redacted]"
        (get-in second-page [:structuredContent :items 0 :command_truncated])
        => true
        (str/includes? (json/generate-string [first-page second-page]) "PRIVATE")
        => false))))

(fact "scheduler failure rolls back storage and reports unknown, never success"
  (with-store
    (fn [_ _ _]
      (with-redefs [cron/runtime! (fn [& _]
                                  (throw (Exception. "PRIVATE-RUNTIME")))]
        (let [reply (call {"action" "create" "schedule" schedule "command" "temps"})]
          (:isError reply) => true
          (:unknownOutcome reply) => true
          (str/includes? (pr-str reply) "PRIVATE") => false))
      (get-in (call {}) [:structuredContent :items]) => [])))

(fact "admin authority is rechecked, never accepted from arguments"
  (with-store
    (fn [_ table url]
      (let [id (:id (first (jdbc/insert! url table
                            {:chat_source_adapter (pr-str "workspace")
                             :chat_source_room "private" :user_id "U2"
                             :schedule schedule :cmd "temps"})))
            item (get-in (call {"action" "get" "id" id}) [:structuredContent :item])]
        (:can_modify item) => false
        (with-redefs [admin/user-is-admin? (constantly true)]
          (get-in (call {"action" "delete" "id" id "version" (:version item)})
                  [:structuredContent :deleted]) => true)))))

(fact "concurrent identical creates register one persisted task only"
  (with-store
    (fn [runtime _ _]
      (let [args {"action" "create" "schedule" schedule "command" "temps"}
            a (future (call args)) b (future (call args))
            replies [@a @b]]
        (mapv :isError replies) => [nil nil]
        (count (distinct (map #(get-in % [:structuredContent :item :id]) replies)))
        => 1
        (count @runtime) => 1
        (count (get-in (call {}) [:structuredContent :items])) => 1))))

(fact "the model can create a requested schedule without a confirmation step"
  (with-store
    (fn [runtime _ _]
      (let [sent (atom [])]
        (with-redefs [mcp/config (constantly {:servers [] :toolsets [toolset]})
                      ai/chat
                      (fn [_ _ messages _]
                        (swap! sent conj messages)
                        {:choices
                         [{:message
                           (if (= 1 (count @sent))
                             {:role "assistant"
                              :tool_calls
                              [{:id "cron-1" :type "function"
                                :function
                                {:name "yetibot_cron"
                                 :arguments (json/generate-string
                                              {"action" "create"
                                               "schedule" schedule
                                               "command" "temps"})}}]}
                             {:role "assistant" :content "Schedule created."})}]})]
          (execution/in-event execution/request "ai schedule temps each morning"
            (fn [event]
              (:result/value
                (tools/complete {:name "fake"} "fake"
                                [{:role "user" :content "Schedule temps at 08:00 server time daily."}]
                                {:actor (policy/actor event)}))
              => "Schedule created."))
          (count @runtime) => 1
          (let [message (first (filter #(= "tool" (:role %)) (last @sent)))
                reply (json/parse-string (:content message) true)]
            (get-in reply [:structuredContent :created]) => true
            (get-in reply [:structuredContent :item :command]) => "temps"
            (get-in reply [:structuredContent :scope]) => "current-channel"))))))
