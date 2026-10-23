(ns yetibot.test.integration.ai-native-tools
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts => contains]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai]
            [yetibot.commands.grok]
            [yetibot.core.config :as settings]
            [yetibot.core.db.history :as history]
            [yetibot.core.db.util :as db]
            [yetibot.core.models.help :as help]
            [yetibot.core.models.history :as stored-history]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.test.support.mcp :as support]))

(def toolset
  {:name "yetibot_read" :kind "search" :servers []
   :native-tools ["help" "history" "history_count"]
   :users ["U1"] :conversations ["workspace/C1"] :adapters ["slack"]
   :providers [{:name "grok" :models ["grok-4.7"]}]})
(def config {:servers [] :toolsets [toolset] :max-tool-calls 4})
(def implicit-config (update-in config [:toolsets 0] dissoc :native-tools))
(def provider {:name "grok" :url "http://localhost/v1"})
(def messages [{:role "user" :content "Find the launch discussion."}])

(defn error-message [f]
  (try (f) (catch Exception e (.getMessage e))))

(defn with-history [f]
  (let [config (assoc (db/config) :table
                      {:prefix (str "native_" (System/nanoTime) "_")})]
    (with-redefs [db/config (constantly config)]
      (let [table (db/qualified-table-name "history")]
        (try
          (jdbc/db-do-commands
            (:url config)
            (jdbc/create-table-ddl
              table (get history/schema :schema/specs)
              {:entities #(string/replace (name %) "-" "_")}))
          (doseq [i (range 25)]
            (history/create
              {:body (str "launch " i) :user-name "alice"
               :chat-source-adapter "\"workspace\""
               :chat-source-room "private" :is-private true
               :created-at (java.sql.Timestamp. 1000)}))
          (doseq [[adapter room body]
                  [["\"other\"" "private" "other-connection-secret"]
                   ["\"workspace\"" "elsewhere" "other-channel-secret"]
                   ["\"workspace\"" "C1" "raw-id-is-not-storage-room"]]]
            (history/create {:body body :chat-source-adapter adapter
                             :chat-source-room room :is-private true}))
          (f)
          (finally
            (jdbc/db-do-commands (:url config) (str "DROP TABLE " table))))))))

(defn run-calls
  "Exercise shared validation/execution; only replace the model boundary."
  [settings calls & [request]]
  (execution/in-event (or request execution/request) "ai find launch"
    (fn [request]
      (let [sent (atom [])]
        (with-redefs [mcp/config (constantly settings)
                      ai/chat
                      (fn [_ _ msgs options]
                        (swap! sent conj {:messages msgs :options options})
                        {:choices
                         [{:message
                           (if (= 1 (count @sent))
                             {:role "assistant"
                              :tool_calls
                              (mapv (fn [i [name args]]
                                      {:id (str "call-" i) :type "function"
                                       :function
                                       {:name (str "yetibot_" name)
                                        :arguments
                                        (json/generate-string args)}})
                                    (range) calls)}
                             {:role "assistant" :content "Done."})}]})]
          (let [result (tools/complete
                         provider "grok-4.7" messages
                         {:actor (policy/actor request)})]
            {:result result :sent @sent
             :replies (mapv #(json/parse-string (:content %) true)
                            (filter #(= "tool" (:role %))
                                    (:messages (last @sent))))}))))))

(facts "native discovery is explicit, visible only to authorized actors"
  (execution/in-event execution/request "ai tools"
    (fn [request]
      (with-redefs [mcp/config (constantly config)]
        (fact (tools/server-names (policy/actor request)) => ["yetibot"])
        (fact (tools/tool-names "yetibot" (policy/actor request))
              => ["yetibot/help" "yetibot/history" "yetibot/history_count"])
        (fact (tools/server-names nil) => [])))))

(facts "built-ins appear alongside legacy MCP tools without an opt-in toolset"
  (execution/in-event execution/request "ai tools"
    (fn [request]
      (let [actor (policy/actor request)
            remote {:name "search" :session {:server {:name "exa"}}}]
        (doseq [settings [nil {:servers []}
                          {:servers [{:name "exa" :tools ["search"]}]}]]
          (with-redefs [mcp/config (constantly settings)
                        mcp/with-tools
                        (fn [cfg f] (f (if (seq (:servers cfg)) [remote] [])))]
            (fact (tools/tool-names "yetibot" actor)
                  => ["yetibot/help" "yetibot/history"
                      "yetibot/history_count"])
            (fact (tools/tool-names nil actor)
                  => (vec (concat (when (seq (:servers settings))
                                    ["exa/search"])
                                  ["yetibot/help" "yetibot/history"
                                   "yetibot/history_count"])))))))))

(facts "implicit built-ins respect named policy and explicit opt-out"
  (execution/in-event execution/request "ai tools"
    (fn [request]
      (let [actor (policy/actor request)
            defaults implicit-config]
        (with-redefs [mcp/config (constantly defaults)]
          (fact (tools/tool-names "yetibot" actor)
                => ["yetibot/help" "yetibot/history"
                    "yetibot/history_count"])
          (fact (tools/server-names (assoc actor :user "U2")) => []))
        (with-redefs [mcp/config
                      (constantly (assoc-in config [:toolsets 0 :native-tools]
                                            []))]
          (fact (tools/server-names actor) => []))))))

(facts "default history keeps current-room scope without native configuration"
  (with-history
    (fn []
      (let [result (run-calls nil [["history" {"limit" 2}]
                                   ["history_count" {}]])]
        (fact (mapv :body (get-in result
                                 [:replies 0 :structuredContent :messages]))
              => ["launch 23" "launch 24"])
        (fact (get-in result [:replies 1 :structuredContent :count]) => 25)))))

(fact "text-only providers still work without MCP or explicit native tools"
  (execution/in-event execution/request "ai explain"
    (fn [request]
      (with-redefs [mcp/config (constantly nil)
                    ai/complete (fn [& _] {:result/value "Plain answer."})
                    ai/chat (fn [& _] (throw (AssertionError. "Tool loop")))]
        (tools/complete (assoc provider :tool-calling false)
                        "text-only" messages {:actor (policy/actor request)})
        => {:result/value "Plain answer."}))))

(facts "real PostgreSQL history is bounded, tied by id, and current-room only"
  (with-history
    (fn []
      (let [result (run-calls config [["history" {"query" "launch"
                                                 "limit" 2}]
                                     ["history_count" {"query" "launch"}]])
            page (get-in result [:replies 0 :structuredContent])
            next-page (run-calls config
                       [["history" {"limit" 2
                                    "before_id" (:next_before page)}]])]
        (fact (mapv :body (:messages page)) => ["launch 23" "launch 24"])
        (fact (:has_more page) => true)
        (fact (:untrusted page) => true)
        (fact (mapv :body (get-in next-page
                                 [:replies 0 :structuredContent :messages]))
              => ["launch 21" "launch 22"])
        (fact (get-in result [:replies 1 :structuredContent :count]) => 25)
        (fact (get-in result [:result :result/data :mcp :tool-calls]) => 2)
        (fact (some #(string/includes? (json/generate-string result) %)
                    ["other-channel-secret" "other-connection-secret"
                     "raw-id-is-not-storage-room"]) => nil)
        (fact (get-in (run-calls config
                       [["history" {"before_id" 28}]])
                      [:replies 0 :structuredContent :messages]) => [])
        (fact (get-in (run-calls config
                       [["history_count" {"query" "%_"}]])
                      [:replies 0 :structuredContent :count]) => 0)))))

(facts "invalid native arguments reject the whole batch before any DB access"
  (doseq [args [{"room" "elsewhere"} {"limit" 0} {"limit" 21}
               {"limit" "2"} {"before_id" -1} {"before_id" 1.5}
               {"query" ""} {"query" (apply str (repeat 201 "x"))}]]
    (with-redefs [jdbc/query (fn [& _]
                               (throw (AssertionError. "Database accessed")))]
      (fact (error-message #(run-calls config
                             [["history_count" {}] ["history" args]]))
            => "Invalid arguments for native Yetibot tool."))))

(facts "help reads docs without executing a command or leaking alias bodies"
  (with-redefs [help/get-docs (constantly {"echo" #{"echo <text> # repeat"}
                                         "history" #{"history # chat history"}})
                help/get-alias-docs (constantly {"secret-alias"
                                                #{"secret-alias TOKEN"}})]
    (let [result (run-calls config [["help" {}]
                                   ["help" {"topic" "echo"}]])]
      (fact (get-in result [:replies 0 :structuredContent :topics])
            => ["echo" "history"])
      (fact (get-in result [:replies 1 :structuredContent :docs])
            => ["echo <text> # repeat"])
      (fact (string/includes? (json/generate-string result) "TOKEN")
            => false))))

(facts "native read batches retain shared duplicate and call-budget checks"
  (with-redefs [jdbc/query (fn [& _]
                             (throw (AssertionError. "Database accessed")))]
    (fact (error-message #(run-calls config
                           [["history_count" {}] ["history_count" {}]]))
          => (contains "repeat a tool call"))
    (fact (error-message #(run-calls (assoc config :max-tool-calls 1)
                           [["history" {}] ["history_count" {}]]))
          => (contains "tool-call limit"))))

(facts "explicit native overrides still validate their names and access policy"
  (fact (error-message #(toolsets/validate!
                         {:servers [] :native-tools ["history"]}))
        => (contains "Invalid AI toolsets"))
  (doseq [t [(dissoc toolset :users) (dissoc toolset :conversations)
             (dissoc toolset :adapters) (dissoc toolset :providers)
             (assoc toolset :native-tools ["exec"])
             (assoc toolset :native-tools ["history" "history"])
             (assoc toolset :adapters ["http"])
             (assoc toolset :servers nil)]]
    (fact (error-message #(toolsets/validate! (assoc config :toolsets [t])))
          => (str "Invalid AI toolsets. Check names, references "
                  "and access policy."))))

(facts "implicit native tools enforce the selected toolset's policy"
  (execution/in-event execution/request "ai find launch"
    (fn [request]
      (let [actor (policy/actor request)]
        (with-redefs [mcp/config (constantly implicit-config)
                      ai/chat (fn [& _]
                                (throw (AssertionError. "Model called")))
                      jdbc/query (fn [& _]
                                   (throw (AssertionError. "DB called")))]
          (doseq [[a p model]
                  [[nil provider "grok-4.7"]
                   [(assoc actor :user "U2") provider "grok-4.7"]
                   [actor {:name "other"} "grok-4.7"]
                   [actor provider "forbidden"]]]
            (fact (string? (error-message
                             #(tools/complete p model messages {:actor a})))
                  => true)))))))

(facts "identity/model policy runs before model or database access"
  (execution/in-event execution/request "ai find launch"
    (fn [request]
      (let [actor (policy/actor request)]
        (with-redefs [mcp/config (constantly config)
                      ai/chat (fn [& _]
                                (throw (AssertionError. "Model called")))
                      jdbc/query (fn [& _]
                                   (throw (AssertionError. "DB called")))]
          (doseq [[a p model]
                  [[nil provider "grok-4.7"]
                   [(assoc actor :user "U2") provider "grok-4.7"]
                   [(assoc actor :conversation "workspace/C2")
                    provider "grok-4.7"]
                   [actor {:name "other"} "grok-4.7"]
                   [actor provider "other"]]]
            (fact (string? (error-message
                             #(tools/complete p model messages {:actor a})))
                  => true)))))))

(fact "a claimed actor without adapter provenance cannot enable native tools"
  (with-redefs [mcp/config (constantly config)]
    (error-message
      #(tools/complete provider "grok-4.7" messages
         {:actor {:adapter "slack" :connection "workspace" :user "U1"
                  :conversation "workspace/C1" :thread "root"}}))
    => "Native Yetibot tools require a trusted chat request."))

(facts "escaped history and help remain valid bounded JSON, with real cursors"
  (with-history
    (fn []
      (doseq [i (range 5)]
        (history/create
          {:body (str "large " i (apply str (repeat 3000 \u0001)))
           :user-name (apply str (repeat 300 "u"))
           :chat-source-adapter "\"workspace\""
           :chat-source-room "private" :created-at (java.sql.Timestamp. 2000)}))
      (let [result (run-calls config [["history" {"limit" 20}]])
            page (get-in result [:replies 0 :structuredContent])
            next-page (run-calls config
                        [["history" {"before_id" (:next_before page)}]])]
        (fact (count (:messages page)) => 1)
        (fact (:truncated page) => true)
        (fact (get-in page [:messages 0 :body-truncated]) => true)
        (fact (get-in page [:messages 0 :user-name-truncated]) => true)
        (fact (count (get-in page [:messages 0 :body])) => 1000)
        (fact (<= (count (json/generate-string (first (:replies result))))
                  12200) => true)
        (fact (string/starts-with?
                (get-in next-page [:replies 0 :structuredContent :messages 0
                                  :body]) "large 3") => true))))
  (with-redefs [help/get-docs
                (constantly {"long" #{(apply str (repeat 10000 \u0001))}})]
    (let [reply (first (:replies (run-calls config
                                  [["help" {"topic" "long"}]])))]
      (fact (get-in reply [:structuredContent :truncated]) => true)
      (fact (<= (count (json/generate-string reply)) 10200) => true))))

(facts "help topic pagination is stable and unknown docs are explicit"
  (with-redefs [help/get-docs
                (constantly {"c" #{"C"} "a" #{"A"} "b" #{"B"}})]
    (let [result (run-calls config [["help" {"limit" 2}]])
          page (get-in result [:replies 0 :structuredContent])]
      (fact (:topics page) => ["a" "b"])
      (fact (:next_after page) => "b")
      (fact (get-in (run-calls config [["help" {"after" "b"}]])
                    [:replies 0 :structuredContent :topics]) => ["c"])
      (fact (get-in (run-calls config [["help" {"topic" "missing"}]])
                    [:replies 0 :structuredContent :found]) => false))))

(facts "PostgreSQL enforces both read-only execution and query cancellation"
  (with-history
    (fn []
      (let [url (:url (db/config))
            table (db/qualified-table-name "history")
            view (str table "_view")
            writer (str table "_writer")]
        (try
          (jdbc/db-do-commands
            url
            [(str "CREATE FUNCTION " writer "() RETURNS boolean AS $$ "
                  "BEGIN INSERT INTO " table "(body) VALUES ('write-marker'); "
                  "RETURN true; END $$ LANGUAGE plpgsql")
             (str "CREATE VIEW " view " AS SELECT * FROM " table
                  " WHERE " writer "()")])
          (with-redefs [db/qualified-table-name (constantly view)]
            (fact (get-in (run-calls config [["history_count" {}]])
                          [:replies 0 :isError]) => true))
          (fact (:count (first (jdbc/query
                                url [(str "SELECT count(*) FROM " table
                                          " WHERE body = 'write-marker'")])))
                => 0)
          (jdbc/db-do-commands
            url [(str "DROP VIEW " view)
                 (str "CREATE VIEW " view " AS SELECT h.* FROM " table
                      " h CROSS JOIN pg_sleep(5)")])
          (with-redefs [db/qualified-table-name (constantly view)]
            (fact (get-in (run-calls config [["history_count" {}]])
                          [:replies 0])
                  => (contains
                       {:isError true
                        :structuredContent
                        {:error "History query failed or timed out."}})))
          (finally
            (jdbc/db-do-commands
              url [(str "DROP VIEW IF EXISTS " view)
                   (str "DROP FUNCTION IF EXISTS " writer "()")])))))))

(fact "real ai and grok commands use native tools over Responses HTTP"
  (with-history
    (fn []
      (let [requests (atom [])]
        (support/with-http-server
          (fn [{:keys [body]}]
            (swap! requests conj body)
            {:body
             {:id "native-response" :status "completed"
              :output
              (if (and (seq (:tools body))
                       (not= "function_call_output"
                             (:type (last (:input body)))))
                [{:type "function_call" :id "output-item" :call_id "read-1"
                  :name "yetibot_history_count"
                  :arguments "{\"query\":\"launch\"}"}]
                [{:type "message" :role "assistant"
                  :content [{:type "output_text"
                             :text "25 launch messages."}]}])}})
          (fn [url]
            (let [p {:name "grok" :url (str url "/v1") :api "responses"
                     :model "grok-4.7" :native-tools ["web_search" "x_search"]}
                  read-config settings/get-config]
              (with-redefs [settings/get-config
                            (fn [spec path]
                              (if (= [:ai] path) {:value {:providers [p]}}
                                (read-config spec path)))
                            mcp/config (constantly config)
                            context/messages (fn [& _] messages)]
                (doseq [settings [nil {:servers []} config implicit-config]
                        cmd ["ai --provider grok find launch"
                             "grok find launch"]]
                  (reset! requests [])
                  (let [result (with-redefs [mcp/config (constantly settings)]
                                 (execution/run-command execution/request cmd))
                        first-turn (first @requests)
                        reply (last (:input (last @requests)))]
                    (fact (string/includes? (:value result)
                                            "25 launch messages.") => true)
                    (fact (:model first-turn) => "grok-4.7")
                    (fact (take 2 (:tools first-turn))
                          => [{:type "web_search"} {:type "x_search"}])
                    (fact (:call_id reply) => "read-1")
                    (fact (get-in (json/parse-string (:output reply) true)
                                  [:structuredContent :count]) => 25)))
                (reset! requests [])
                (execution/run-command execution/request
                                       "grok --no-tools find launch")
                (fact (count @requests) => 1)
                (fact (empty? (:tools (first @requests))) => true)))))))))

(facts "Discord history uses the incoming stored channel, not another room"
  (with-history
    (fn []
      (doseq [[room body] [["D1" "incoming thread"]
                           ["parent" "not this channel"]]]
        (history/create {:chat-source-adapter ":discord-prod"
                         :chat-source-room room :body body}))
      (let [request (assoc execution/request :chat-source
                          {:adapter :discord :uuid :discord-prod :room "D1"
                           :raw-event {:author {:id "U1"} :channel-id "D1"
                                       :id "M1"}})
            settings (-> config
                         (assoc-in [:toolsets 0 :adapters] ["discord"])
                         (assoc-in [:toolsets 0 :conversations]
                                   [":discord-prod/D1"]))
            result (run-calls settings [["history" {}]] request)]
        (fact (mapv :body (get-in result
                                 [:replies 0 :structuredContent :messages]))
              => ["incoming thread"])))))

(facts "disabled history prevents reads and configured secrets stay redacted"
  (with-redefs [stored-history/history-enabled (constantly false)
                jdbc/query (fn [& _]
                             (throw (AssertionError. "Database accessed")))]
    (fact (get-in (run-calls config [["history" {}]]) [:replies 0])
          => (contains {:isError true
                        :structuredContent {:error "History is disabled."}})))
  (with-history
    (fn []
      (with-redefs [provider (assoc provider :key "launch")]
        (fact (string/includes?
                (json/generate-string
                  (:replies (run-calls config [["history" {}]])))
                "launch") => false))
      (dotimes [_ 20]
        (history/create {:chat-source-adapter (pr-str "workspace")
                         :chat-source-room "private"
                         :body (apply str (repeat 1000 "u"))}))
      (with-redefs [provider (assoc provider :key "u")]
        (fact (get-in (run-calls config [["history" {"limit" 20}]])
                      [:replies 0])
              => (contains
                   {:isError true
                    :structuredContent
                    {:error "Native result exceeded the output limit."
                     :truncated true}}))))))
