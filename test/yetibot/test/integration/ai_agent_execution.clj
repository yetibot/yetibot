(ns yetibot.test.integration.ai-agent-execution
  (:require [midje.sweet :refer [fact => =not=> throws]]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.jdbc :as jdbc]
            [clj-http.client :as http]
            [hara.io.scheduler :as scheduler]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.cloudflare :as cloudflare]
            [yetibot.api.clef :as clef]
            [yetibot.commands.ai :as ai-command]
            [yetibot.commands.grok :as grok]
            [yetibot.core.config :as host-config]
            [yetibot.commands.openai :as openai]
            [yetibot.commands.weather :as weather]
            [yetibot.core.commands.agent :as core-agent]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.commands.collections]
            [yetibot.core.commands.echo]
            [yetibot.core.adapters.adapter :as adapters]
            [yetibot.core.adapters.slack :as slack]
            [yetibot.core.adapters.discord :as discord]
            [clj-slack.chat :as slack-chat]
            [yetibot.core.webapp.routes.images :as images]
            [yetibot.core.models.users :as users]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.models.admin :as admin]
            [yetibot.core.db :as database]
            [yetibot.core.db.util :as db]
            [yetibot.core.db.agent-run :as ledger]
            [yetibot.core.db.ai-usage :as usage-store]
            [yetibot.core.db.cron :as cron]
            [yetibot.core.handler :as handler]
            [yetibot.core.webapp.routes.api :as http-api]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.interpreter :as interpreter]
            [yetibot.core.chat :as chat]
            [yetibot.core.util.agent-execution :as origin]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-agent-runs :as runs]
            [yetibot.models.ai-datasets :as datasets]
            [yetibot.models.ai-kroki-tools :as kroki]
            [yetibot.core.util.public-result :as public]
            [clojure.string :as string]
            [yetibot.models.ai-cron-tools :as native-cron]
            [yetibot.util.ai-execution :as execution]
            [yetibot.util.ai-agent-execution :as agent]
            [yetibot.test.support.ai-execution :as support]
            [yetibot.test.support.mcp :as scripted]))

(def provider {:name "fixture" :model "fixture-model" :url "https://fixture.invalid/v1"})
(def toolset {:name "integration" :kind "search" :servers []
              :native-tools ["command" "cron"] :users ["U1"] :adapters ["slack"]
              :providers [{:name "fixture" :models ["fixture-model"]}]})
(defn answer [text] {:choices [{:message {:role "assistant" :content text}}]})
(defn tool-call [command]
  {:choices [{:message {:role "assistant" :content nil
                        :tool_calls [{:id "dispatch" :type "function"
                                      :function {:name "yetibot_command"
                                                 :arguments (json/generate-string {"command" command})}}]}}]})

(def ^:dynamic *deliveries* nil)

(def adapter
  (reify adapters/Adapter
    (uuid [_] "workspace") (platform-name [_] "Slack")
    (channels [_] ["C1"]) (connected? [_] true)
    (send-msg [this text]
      (when *deliveries* (swap! *deliveries* conj [this chat/*target* chat/*thread-ts* text]))
      nil)
    (send-paste [_ _] nil)
    (join [_ _]) (leave [_ _]) (chat-source [_ _])
    (start [_]) (stop [_])
    (connection-last-active-timestamp [_] 0) (connection-latency [_] 0)))

(def ^:dynamic *queued* nil)
(def ^:dynamic *schedule-failure* nil)

(defn with-runtime [f]
  (let [cfg (db/config) prefix (str "app_u3_" (System/nanoTime) "_")
        callbacks agent/callbacks queued (atom []) failed (atom false)]
    (support/configured-test-url! cfg (System/getenv "YETIBOT_DB_URL"))
    (with-redefs [db/config (constantly (assoc cfg :table {:prefix prefix}))
                  adapters/adapters (atom {"workspace" adapter})
                  users/users (atom {{:adapter :slack :id "U1"} (:user support/request)
                                     {:adapter :slack :id "BOT"} {:id "BOT"}})
                  hooks/hooks (atom @hooks/hooks)
                  hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                  channel/settings-for-chat-source (constantly {})
                  history/add (fn [& _])
                  context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                  mcp/config (constantly {:servers [] :toolsets [toolset]})
                  ai/config (constantly {:providers [provider]})
                  agent/callbacks (fn [options]
                                    (callbacks (assoc options :schedule
                                              (fn [f]
                                                (when (not= true @failed)
                                                  (swap! queued conj (bound-fn [] (f))))
                                                (when @failed (throw (Exception. "scripted schedule failure")))))))]
      (try
        (doseq [schema [ledger/schema cron/schema]]
          (database/idempotent-create-table! (:schema/table schema) (:schema/specs schema)))
        (agent/install!)
        (hooks/cmd-hook #"agent" #"run\s+(.+)" core-agent/agent-run-cmd
                        #"(?s)(.+)" core-agent/agent-cmd)
        (binding [*queued* queued *schedule-failure* failed] (f (:url cfg)))
        (finally
          (doseq [table ["agent_run" "cron"]]
            (jdbc/db-do-commands (:url cfg)
              (str "DROP TABLE IF EXISTS " (db/qualified-table-name table)))))))))

(defn dispatch [text]
  (support/in-event support/request text
    (fn [{:keys [chat-source user]}]
      (binding [interpreter/*chat-source* chat-source chat/*target* "C1"
                chat/*adapter* adapter]
        (handler/record-and-run-raw (str "!" text) user {:id "BOT"}
                                    {:record-yetibot-response? false})))))

(fact "public no-tools source sends once before derived agent admission; no CLI or new allowance"
  (with-runtime
    (fn [url]
      (let [wire (atom []) queued (atom [])]
        (with-redefs [http/post (fn [endpoint opts]
                                 (swap! wire conj [endpoint (:form-params opts)])
                                 {:status 200 :body (answer "agent summarize")})
                      ]
          ;; xargs really reparses model-produced executable text.
          (dispatch "ai --no-tools --max-output-tokens 91 report | split , | xargs %s")
          (count @wire) => 1
          (get-in @wire [0 1 :max_completion_tokens]) => 91
          (let [rows (jdbc/query url [(str "SELECT * FROM " (db/qualified-table-name "agent_run"))]
                                 {:identifiers #(clojure.string/replace % "_" "-")})
                row (first rows) state (runs/decode (:execution-state row) {})]
            (count rows) => 1
            (:execution-limit row) => 18
            (:generation-cap row) => 91
            (get-in state [:authority :tools]) => #{}
            (get-in state [:authority :user :id]) => "U1"
            (get-in state [:authority :source :room]) => "private"
            (get-in state [:authority :planner :model]) => "fixture-model"))))))

(fact "native xAI optimization and image sends have separate ordinals and actual inherited field"
  (with-runtime
    (fn [_]
      (let [wire (atom []) request (execution/family 3 73)]
        (with-redefs [xai/config {:key "synthetic-key"}
                      mcp/config (constantly {:servers [] :toolsets [(update toolset :providers conj
                                                  {:name "xai" :models ["grok-4.7" "grok-imagine-image-2.0"]})]})
                      http/post (fn [url opts]
                                  (swap! wire conj [url (json/parse-string (:body opts) true)
                                                   (:ordinal origin/*operation*)])
                                  {:status 200 :body
                                   (if (.endsWith url "/responses")
                                     {:output [{:type "message" :content [{:text "optimized"}]}]}
                                     {:data [{:b64_json "AA=="}]})})]
          (support/in-event support/request "native image"
            (fn [event] (execution/with-request* (assoc request :event event) #(xai/generate-image "synthetic"))))
          (mapv first @wire) => ["https://api.x.ai/v1/responses"
                                 "https://api.x.ai/v1/images/generations"]
          (get-in @wire [0 1 :max_output_tokens]) => 73
          (get-in @wire [1 1 :n]) => 1
          (mapv #(nth % 2) @wire) => [1 2]
          (:used @(:budget request)) => 2)))))

(fact "real core tick is consumed once using current counters, never double claimed"
  (with-runtime
    (fn [url]
      (let [state {:job-id "cron-fixture" :family-id "finite" :phase :queued
                   :generation-cap 91 :scheduled? true
                   :authority {:source (:chat-source support/request)
                               :user (:user support/request) :yetibot-user {:id "BOT"}
                               :actor {:adapter "slack" :connection "workspace" :user "U1"
                                       :conversation "workspace/C1" :thread "root"}
                               :delivery {:source (:chat-source support/request) :target "C1"}
                               :tools #{} :toolset-name "integration" :toolset-policy toolset
                               :planner {:name "fixture" :model "fixture-model"
                                         :signature (execution/provider-signature provider)}}}
            row (jdbc/with-db-transaction [tx url]
                  (ledger/create-inherited! tx "cron"
                    {:schedule "0 0 8 * * * *" :cmd "echo tick" :execution-origin ledger/inherited-kind
                     :execution-state (runs/encode state {})}
                    {:limit 3 :generation-cap 91}))
            claim (jdbc/with-db-transaction [tx url] (cron/claim-tick! tx (:id row)))
            session (runs/claimed-cron-session url claim {})]
        (:used @(:budget session)) => 1
        (:execution-phase @(:row session)) => "operation-in-flight"
        (:operation-ordinal @(:row session)) => 1
        (runs/checkpoint! session @(:state session) (:operation claim))
        (:execution-phase (ledger/read-row url "cron" (:id row))) => "ready"
        (runs/claimed-cron-session url claim {}) => (throws Exception)))))

(fact "real command alias agent queues once; restored worker writes as original caller with three wire/tool admissions"
  (with-runtime
    (fn [url]
      (let [sends (atom []) effects (atom [])]
        (hooks/cmd-hook-resolved #"synthetic-write" #".*"
          (fn [input] (swap! effects conj (select-keys input [:user :yetibot-user :chat-source]))
            {:result/value "written"}))
        (#'alias/wire-alias {:cmd-name "handoff" :cmd "agent do the requested work"})
        (with-redefs [http/post (fn [endpoint opts]
                                 (swap! sends conj [endpoint (:form-params opts)])
                                 {:status 200 :body
                                  (case (count @sends)
                                    1 (tool-call "handoff")
                                    2 (tool-call "synthetic-write")
                                    (answer "finished"))})]
          (dispatch "ai --max-output-tokens 91 delegate")
          (count @*queued*) => 1
          (count @sends) => 1
          (count @effects) => 0
          ;; Bound scheduling emulates future's real conveyed bindings, not a
          ;; new fake actor/resolver. The worker itself must clear the old op.
          ((first @*queued*))
          (count @sends) => 3
          (mapv #(get-in % [1 :model]) @sends) => ["fixture-model" "fixture-model" "fixture-model"]
          (mapv #(get-in % [1 :max_completion_tokens]) @sends) => [91 91 91]
          (count @effects) => 1
          (get-in @effects [0 :user :id]) => "U1"
          (get-in @effects [0 :yetibot-user :id]) => "BOT"
          (get-in @effects [0 :chat-source :room]) => "private"
          (let [row (first (jdbc/query url [(str "SELECT * FROM " (db/qualified-table-name "agent_run"))]
                                     {:identifiers #(clojure.string/replace % "_" "-")}))]
            (:execution-used row) => 3
            (:execution-phase row) => "terminal"
            (:execution-state row) => nil))))))

(fact "ordinary admin and category denials remain before effect admission on the actual restored dispatcher"
  (with-runtime
    (fn [_]
      (doseq [denial [:admin :category]]
        (let [sends (atom 0) effects (atom 0)]
          (hooks/cmd-hook-resolved #"protected-write" #".*"
            (with-meta (fn [_] (swap! effects inc) {:result/value "forbidden"})
                       {:yb/cat #{:private-write}}))
          (with-redefs [admin/admin-only-command? #(and (= denial :admin) (= % "protected-write"))
                        admin/user-is-admin? (constantly false)
                        channel/settings-for-chat-source
                        (constantly (if (= denial :category) {"disabled-categories" #{:private-write}} {}))
                        http/post (fn [_ _]
                                    (swap! sends inc)
                                    {:status 200 :body
                                     (case @sends 1 (tool-call "agent do work")
                                       2 (tool-call "protected-write") (answer "denied"))})]
            (reset! *queued* [])
            (dispatch "ai delegate")
            ((first @*queued*))
            @effects => 0
            @sends => 3))))))

(fact "human literal standalone, alias and payload pipelines keep the existing legacy branch"
  (with-runtime
    (fn [url]
      (let [legacy (atom 0) sends (atom 0)]
        (#'alias/wire-alias {:cmd-name "human-agent" :cmd "agent summarize $s"})
        (with-redefs [core-agent/configured? (constantly false)
                      core-agent/say-unconfigured (fn [] (swap! legacy inc) "legacy-unconfigured")
                      http/post (fn [_ _] (swap! sends inc)
                                  {:status 200 :body (answer "ordinary report")})]
          (doseq [text ["agent summarize" "human-agent report"
                        "echo payload | agent summarize payload"
                        "ai --no-tools report | agent summarize this report"]]
            (dispatch text))
          @legacy => 4
          @sends => 1
          (count @*queued*) => 0
          (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run"))]) => [])))))

(fact "restored original/current tool/provider authority cannot be widened or forged"
  (with-runtime
    (fn [_]
      (let [wire (atom 0)]
        (with-redefs [http/post (fn [_ _] (swap! wire inc)
                                  {:status 200 :body (tool-call "agent do work")})]
          (dispatch "ai delegate")
          (count @*queued*) => 1
          (with-redefs [mcp/config (constantly {:servers []
                                              :toolsets [(assoc toolset :users ["REVOKED"])]})]
            ((first @*queued*)) => (throws Exception))
          @wire => 1
          (origin/agent-route {:chat-source {:adapter :slack :uuid "workspace"}
                               :user {:id "U1"}}) => (throws Exception)
          (origin/invoke! {:callbacks {:start identity}} :start {}) => (throws Exception)
          (with-redefs [adapters/adapters (atom {})]
            (#'agent/current-authority
              {:source (:chat-source support/request) :user (:user support/request)
               :yetibot-user {:id "BOT"} :actor {:user "U1"} :tools #{}})
            => (throws Exception)))))))

(defn scheduler-functions []
  (with-redefs [scheduler/start! identity]
    (require 'yetibot.core.commands.cron)
    (try @(ns-resolve 'yetibot.core.commands.cron 'loader) (catch Exception _ nil)))
  {:wire (ns-resolve 'yetibot.core.commands.cron 'wire-cron!)
   :command (ns-resolve 'yetibot.core.commands.cron 'cron-cmd)})

(fact "actual core cron and native schedule-only conversion persist finite sticky grants; stale callbacks reload"
  (with-runtime
    (fn [url]
      (let [{:keys [wire command]} (scheduler-functions)
            tasks (atom {}) effects (atom 0) turns (atom 0)
            manage @#'native-cron/manage! errors (atom [])]
        (hooks/cmd-hook-resolved #"cron" #"(\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+)\s+(.+)" command)
        (hooks/cmd-hook-resolved #"tick-write" #".*"
          (fn [_] (swap! effects inc) {:result/value "tick done"}))
        (with-redefs [native-cron/manage! (fn [& args]
                                          (try (apply manage args)
                                               (catch Exception e
                                                 (swap! errors conj [(str (class e)) (.getMessage e)])
                                                 (throw e))))
                      scheduler/add-task (fn [_ id task] (swap! tasks assoc id task))
                      scheduler/delete-task (fn [_ id] (swap! tasks dissoc id))
                      http/post (fn [_ _] (swap! turns inc)
                                  {:status 200 :body (tool-call "cron 0 0 8 * * * * tick-write")})]
          (dispatch "ai --max-tool-calls 5 --max-output-tokens 91 create schedule")
          (let [row (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "cron"))]))
                saved (ledger/read-row url "cron" (:id row))
                callback (get-in @tasks [(str "task-" (:id row)) :handler])]
            (:execution-origin saved) => ledger/inherited-kind
            (:execution-limit saved) => 3
            (:execution-used saved) => 0
            (:generation-cap saved) => 91
            (dotimes [_ 4] (callback nil))
            @effects => 1
            (:execution-phase (ledger/read-row url "cron" (:id row))) => "unknown"
            (:execution-used (ledger/read-row url "cron" (:id row))) => 1
            ;; Ordinary later edit retains original family and exhausted ledger.
            (dispatch (str "cron 0 0 9 * * * * tick-write"))
            (let [legacy (first (jdbc/insert! url (db/qualified-table-name "cron")
                                 {:chat_source_adapter (pr-str "workspace")
                                  :chat_source_room "private" :user_id "U1"
                                  :schedule "0 0 8 * * * *" :cmd "tick-write"}))
                  _ (wire (ledger/read-row url "cron" (:id legacy)))
                  stale (get-in @tasks [(str "task-" (:id legacy)) :handler])
                  parent (merge (execution/family 4 91)
                                {:planner-provider provider :planner-model "fixture-model"
                                 :toolset-name "integration" :toolset-policy toolset
                                 :tool-ceiling (atom #{["yetibot" "command"]})})]
              (support/in-event support/request "ai update schedule"
                (fn [event]
                  (binding [chat/*target* "C1"]
                    (execution/with-request* (assoc parent :event execution/*event*)
                      (fn []
                        (let [get-result (native-cron/call! {:toolset toolset} "cron"
                                           {"action" "get" "id" (:id legacy)} (:chat-source event))
                              version (get-in get-result [:structuredContent :item :version])
                              update-result (native-cron/call! {:toolset toolset} "cron"
                                              {"action" "update" "id" (:id legacy) "version" version
                                               "schedule" "0 0 9 * * * *"} (:chat-source event))]
                          @errors => []
                          (:isError update-result) => nil))))))
              (:reserved @(:budget parent)) => 4
              (:execution-origin (ledger/read-row url "cron" (:id legacy))) => ledger/inherited-kind
              (dotimes [_ 5] (stale nil))
              @effects => 2
              (:execution-phase (ledger/read-row url "cron" (:id legacy))) => "unknown"
              (:execution-used (ledger/read-row url "cron" (:id legacy))) => 1)))))))

(fact "actual native xAI source transport facts normalize map caps without requiring an ai.providers entry"
  (with-runtime
    (fn [_]
      (let [wire (atom []) root-turn (atom 0)
            native-policy (update toolset :providers conj
                                  {:name "xai" :models ["grok-4.7" "grok-imagine-image-2.0"]})]
        (hooks/cmd-hook-resolved #"native-image" #".*"
          (fn [_] (xai/generate-image "synthetic prompt") {:result/value "image generated"}))
        (with-redefs [mcp/config (constantly {:servers [] :toolsets [native-policy]})
                      xai/config {:key "synthetic-native-key"}
                      http/post
                      (fn [url options]
                        (swap! wire conj {:url url :form (or (:form-params options)
                                                           (json/parse-string (:body options) true))
                                         :operation origin/*operation*})
                        {:status 200 :body
                         (cond
                           (= url "https://fixture.invalid/v1/chat/completions")
                           (if (= 1 (swap! root-turn inc)) (tool-call "native-image") (answer "done"))
                           (= url "https://api.x.ai/v1/responses")
                           {:output [{:type "message" :content [{:text "optimized prompt"}]}]}
                           :else {:data [{:b64_json "AA=="}]})})]
          (dispatch "ai --max-output-tokens 73 image")
          (mapv :url @wire) => ["https://fixture.invalid/v1/chat/completions"
                                "https://api.x.ai/v1/responses"
                                "https://api.x.ai/v1/images/generations"
                                "https://fixture.invalid/v1/chat/completions"]
          (get-in @wire [1 :form :max_output_tokens]) => 73
          (get-in @wire [2 :form :n]) => 1
          (mapv #(get-in % [:operation :ordinal]) @wire) => [1 3 4 5]
          (mapv #(get-in % [:operation :parent-ordinal]) @wire) => [nil 2 2 nil])))))

(fact "actual weather command capture observes nil and malformed readings as failed inputs"
  (with-runtime
    (fn [_]
      (hooks/cmd-hook #"weather" #".+" weather/weather-cmd)
      (doseq [case [:nil :malformed]]
        (let [observed (atom []) current weather/current]
          (with-redefs [weather/current (if (= case :nil) (constantly nil) current)
                        weather/get-json (constantly {:current_condition [{:temp_C "not-numeric"}]})]
            (binding [weather/*lookup-observer* #(swap! observed conj %)]
              (dispatch "weather synthetic-city"))
            @observed => [{:input "synthetic-city" :failed? true}]))))))

(fact "completed no-tools output reentering ai cannot regain tools, a larger cap or an open family after exit"
  (with-runtime
    (fn [_]
      (let [wire (atom []) saved-context (atom nil)]
        (with-redefs [http/post
                      (fn [url options]
                        (reset! saved-context origin/*context*)
                        (swap! wire conj [url (:form-params options)
                                         (execution/admissions execution/*request*)])
                        {:status 200 :body
                         (answer (if (= 1 (count @wire))
                                   "ai --max-output-tokens 262144 --max-tool-calls 128 report"
                                   "bounded answer"))})]
          (dispatch "ai --no-tools --max-output-tokens 91 report | split , | xargs %s")
          (count @wire) => 2
          (mapv #(get-in % [1 :max_completion_tokens]) @wire) => [91 91]
          (every? #(empty? (get-in % [1 :tools])) @wire) => true
          (get-in @wire [1 2]) => {:limit 20 :admitted 3 :reserved 0 :remaining 17}
          (origin/invoke! @saved-context :generation-limit) => (throws Exception)
          (origin/invoke! @saved-context :start "late request") => (throws Exception)
          (count @wire) => 2)))))

(fact "no-tools generated text and alias continuations queue text-only, while generic and agent-run effects are forbidden"
  (with-runtime
    (fn [_]
      (let [effects (atom 0) sends (atom [])]
        (hooks/cmd-hook-resolved #"closed-write" #".*"
          (fn [_] (swap! effects inc) {:result/value "written"}))
        (#'alias/wire-alias {:cmd-name "text-agent" :cmd "agent report $s"})
        (#'alias/wire-alias {:cmd-name "effect-alias" :cmd "closed-write"})
        (doseq [generated ["closed-write" "effect-alias" "agent run closed-write"]]
          (with-redefs [http/post (fn [_ _] {:status 200 :body (answer generated)})]
            (dispatch "ai --no-tools report | split , | xargs %s")))
        @effects => 0
        (count @*queued*) => 0
        (with-redefs [http/post (fn [_ options]
                                 (swap! sends conj (:form-params options))
                                 {:status 200 :body (answer (if (= 1 (count @sends))
                                                             "text-agent report" "text completed"))})]
          (dispatch "ai --no-tools --max-output-tokens 91 report | split , | xargs %s")
          (count @*queued*) => 1
          ((last @*queued*))
          (count @sends) => 2
          (every? #(empty? (:tools %)) @sends) => true
          (mapv :max_completion_tokens @sends) => [91 91])))))

(fact "original native xAI text selects and restores its actual transport before IO without an ai.providers entry"
  (with-runtime
    (fn [url]
      (let [wire (atom [])]
        (hooks/cmd-hook-resolved #"native-text" #".*"
          (fn [_] {:result/value (:text (xai/generate-text "source prompt"))}))
        (with-redefs [xai/config {:key "synthetic-native-key"}
                      mcp/config (constantly {:servers [] :toolsets [(update toolset :providers conj
                                                           {:name "xai" :models ["grok-4.7"]})]})
                      http/post (fn [endpoint options]
                                  (swap! wire conj [endpoint (json/parse-string (:body options) true)
                                                   (:ordinal origin/*operation*)])
                                  {:status 200 :body {:output [{:type "message" :content
                                    [{:text (if (= 1 (count @wire)) "agent summarize" "native continuation")}]}]}})]
          (dispatch "native-text | split , | xargs %s")
          (count @*queued*) => 1
          (let [row (first (jdbc/query url [(str "SELECT * FROM " (db/qualified-table-name "agent_run"))]
                                     {:identifiers #(clojure.string/replace % "_" "-")}))
                state (runs/decode (:execution-state row) {})]
            (get-in state [:authority :planner :transport]) => "xai-text"
            (get-in state [:authority :producer :model]) => "grok-4.7")
          ((first @*queued*))
          (mapv first @wire) => ["https://api.x.ai/v1/responses" "https://api.x.ai/v1/responses"]
          (mapv #(get-in % [1 :model]) @wire) => ["grok-4.7" "grok-4.7"]
          (mapv #(get-in % [1 :max_output_tokens]) @wire) => [32768 32768])))))

(fact "original non-chat source selects permitted configured planner before any optimization IO and never falls back after failure"
  (with-runtime
    (fn [url]
      (let [wire (atom []) native-policy (update toolset :providers conj
                                               {:name "xai" :models ["grok-4.7" "grok-imagine-image-2.0"]})]
        (hooks/cmd-hook-resolved #"native-picture" #".*"
          (fn [_] (xai/generate-image "picture") {:result/value "agent summarize picture"}))
        (with-redefs [mcp/config (constantly {:servers [] :toolsets [native-policy]})
                      xai/config {:key "synthetic-native-key"}
                      http/post (fn [endpoint options]
                                  (swap! wire conj [endpoint (:planner-model execution/*request*)
                                                   (:producer execution/*request*)])
                                  {:status 200 :body
                                   (cond
                                     (.endsWith endpoint "/responses")
                                     {:output [{:type "message" :content [{:text "optimized"}]}]}
                                     (.endsWith endpoint "/images/generations") {:data [{:b64_json "AA=="}]}
                                     :else (answer "picture report"))})]
          (dispatch "native-picture | split , | xargs %s")
          (mapv second @wire) => ["fixture-model" "fixture-model"]
          (mapv #(get-in % [2 :model]) @wire) => ["grok-imagine-image-2.0" "grok-imagine-image-2.0"]
          (count @*queued*) => 1
          ((first @*queued*))
          (mapv first @wire) => ["https://api.x.ai/v1/responses"
                                 "https://api.x.ai/v1/images/generations"
                                 "https://fixture.invalid/v1/chat/completions"]
          (let [before (count @wire)]
            (with-redefs [mcp/config (constantly {:servers [] :toolsets
                                                [(assoc native-policy :providers [{:name "xai" :models ["grok-4.7" "grok-imagine-image-2.0"]}])]})]
              (dispatch "native-picture"))
            (count @wire) => before))
        ;; Missing default denies before source IO, rather than changing producer.
        (with-redefs [ai/config (constantly {:providers []})
                      http/post (fn [& _] (swap! wire conj :unexpected))]
          (dispatch "native-picture"))
        (some #{:unexpected} @wire) => nil))))

(fact "actual saved delivery owns original adapter target and thread once; adapter return is not confirmation"
  (with-runtime
    (fn [url]
      (let [sent (atom []) wire (atom 0)]
        (binding [*deliveries* sent]
         (with-redefs [http/post (fn [_ _]
                                  (swap! wire inc)
                                  {:status 200 :body (answer (if (= 1 @wire) "agent report" "saved answer"))})]
          (dispatch "ai --no-tools report | split , | xargs %s")
          (let [row (first (jdbc/query url [(str "SELECT * FROM " (db/qualified-table-name "agent_run"))]
                                     {:identifiers #(clojure.string/replace % "_" "-")}))]
            (binding [chat/*target* "WRONG" chat/*thread-ts* "WRONG"] ((first @*queued*)))
            (count @sent) => 1
            (mapv #(subvec % 1) @sent) => [["C1" nil "saved answer"]]
            ((first @*queued*)) => {:status :not-owner}
            @wire => 2
            (count @sent) => 1
            (:execution-state (ledger/read-row url "agent_run" (:id row))) => nil)))))))

(fact "restart readiness recovers the same genuinely unscheduled row once after the current adapter is connected"
  (with-runtime
    (fn [url]
      (let [wire (atom 0)]
        (reset! *schedule-failure* true)
        (with-redefs [http/post (fn [_ _] (swap! wire inc)
                                 {:status 200 :body (answer (if (= 1 @wire) "agent report" "completed"))})]
          (dispatch "ai --no-tools report | split , | xargs %s")
          (count @*queued*) => 0
          (let [id (:id (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run"))])))]
            (reset! *schedule-failure* false)
            (with-redefs [adapters/adapters (atom {})]
              (support/in-event support/request "echo readiness" (fn [_] nil)))
            (count @*queued*) => 0
            (dotimes [_ 2]
              (support/in-event support/request "echo readiness" (fn [_] nil)))
            (count @*queued*) => 1
            ((first @*queued*)) => {:status :unconfirmed :work-outcome :completed}
            @wire => 2
            (:execution-phase (ledger/read-row url "agent_run" id)) => "terminal"
            (support/in-event support/request "echo readiness" (fn [_] nil))
            (count @*queued*) => 1
            (count (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run"))])) => 1))))))

(fact "concurrent actual cron callbacks cannot overlap or replay an interrupted enclosing tick"
  (with-runtime
    (fn [url]
      (let [{:keys [command]} (scheduler-functions)
            tasks (atom {}) effects (atom 0) entered (promise) release (promise)]
        (hooks/cmd-hook-resolved #"cron" #"(\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+)\s+(.+)" command)
        (hooks/cmd-hook-resolved #"blocking-tick" #".*"
          (fn [_] (swap! effects inc) (deliver entered true) @release {:result/value "done"}))
        (with-redefs [scheduler/add-task (fn [_ id task] (swap! tasks assoc id task))
                      scheduler/delete-task (fn [_ id] (swap! tasks dissoc id))
                      http/post (fn [_ _] {:status 200 :body (tool-call "cron 0 0 8 * * * * blocking-tick")})]
          (dispatch "ai --max-tool-calls 5 create schedule")
          (let [row (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "cron"))]))
                callback (get-in @tasks [(str "task-" (:id row)) :handler])
                first-tick (future (callback nil))]
            (deref entered 5000 false) => true
            (callback nil)
            (deliver release true)
            (deref first-tick 5000 :timeout) =not=> :timeout
            (callback nil)
            @effects => 1
            (:execution-used (ledger/read-row url "cron" (:id row))) => 1
            (:execution-phase (ledger/read-row url "cron" (:id row))) => "unknown"))))))

(fact "actual Gemini image and Veo starts use finite physical admissions; polling and downloads are not fabricated generations"
  (with-runtime
    (fn [_]
      (doseq [kind [:image :video]]
        (let [wire (atom []) reads (atom []) turns (atom 0)
              gemini-policy (update toolset :providers conj
                                    {:name "gemini" :models [(gemini/gemini-model) (gemini/veo-model)]})]
          (hooks/cmd-hook-resolved #"native-gemini" #".*"
            (fn [_] (if (= kind :image) (gemini/generate-image "picture")
                       (gemini/generate-video "clip"))
              {:result/value "media generated"}))
          (with-redefs [mcp/config (constantly {:servers [] :toolsets [gemini-policy]})
                        gemini/config {:key "synthetic-gemini-key"}
                        http/post (fn [endpoint options]
                                    (swap! wire conj {:url endpoint
                                                     :form (or (:form-params options)
                                                               (json/parse-string (:body options) true))
                                                     :operation origin/*operation*})
                                    {:status 200 :body
                                     (cond
                                       (= endpoint "https://fixture.invalid/v1/chat/completions")
                                       (if (= 1 (swap! turns inc)) (tool-call "native-gemini") (answer "done"))
                                       (= kind :image)
                                       {:candidates [{:content {:parts [{:inlineData {:data "AA==" :mimeType "image/png"}}]}}]}
                                       :else {:name "operations/synthetic"})})
                        http/get (fn [endpoint options]
                                   (swap! reads conj [endpoint (:ordinal origin/*operation*)])
                                   {:status 200 :body (if (= :byte-array (:as options)) (byte-array [0])
                                                       {:done true :response {:generateVideoResponse
                                                         {:generatedSamples [{:video {:uri "https://media.invalid/video.mp4"}}]}}})})]
            (dispatch "ai --max-output-tokens 73 media")
            (count @wire) => 3
            (mapv #(get-in % [:operation :ordinal]) @wire) => [1 3 4]
            (mapv #(get-in % [:operation :parent-ordinal]) @wire) => [nil 2 nil]
            (if (= kind :image)
              (do
                (get-in @wire [1 :form :generationConfig :maxOutputTokens]) => 73
                (get-in @wire [1 :form :generationConfig :candidateCount]) => 1
                @reads => [])
              (do
                (get-in @wire [1 :form :parameters :durationSeconds]) => (gemini/veo-duration)
                (contains? (get-in @wire [1 :form]) :max_output_tokens) => false
                (mapv second @reads) => [3 3]))))))))

(fact "provider replacement after allocation cannot redirect a delegated child before its physical send"
  (with-runtime
    (fn [_]
      (let [child {:name "child" :model "child-model" :url "https://child.invalid/v1"}
            cfg {:providers [provider child]}
            allowed (update toolset :providers conj {:name "child" :models ["child-model"]})
            wire (atom [])]
        (hooks/cmd-hook-resolved #"child-inference" #".*"
          (fn [_] (ai/complete (ai/provider (ai/config) "child") "child-model"
                              [{:role "user" :content "child"}])))
        (with-redefs [mcp/config (constantly {:servers [] :toolsets [allowed]})
                      ai/config (constantly cfg)
                      http/post (fn [endpoint _]
                                  (swap! wire conj endpoint)
                                  {:status 200 :body (if (= 1 (count @wire))
                                                      (tool-call "agent child work")
                                                      (tool-call "child-inference"))})]
          (dispatch "ai delegate")
          (with-redefs [ai/config (constantly (assoc-in cfg [:providers 1 :url] "https://replacement.invalid/v1"))]
            (try ((first @*queued*)) (catch Exception _ nil)))
          @wire => ["https://fixture.invalid/v1/chat/completions" "https://fixture.invalid/v1/chat/completions"])))))

(fact "legacy OpenAI source and restored continuation keep its configured native text transport, not app defaults"
  (with-runtime
    (fn [_]
      (let [wire (atom []) allowed (update toolset :providers conj {:name "openai" :models ["gpt-4"]})]
        (with-redefs [mcp/config (constantly {:servers [] :toolsets [allowed]})
                      openai/config {:key "synthetic-native-key"}
                      http/post (fn [endpoint options]
                                  (swap! wire conj [endpoint (:form-params options)])
                                  {:status 200 :body (answer (if (= 1 (count @wire)) "agent summarize" "native answer"))})]
          (dispatch "openai report | split , | xargs %s")
          (count @*queued*) => 1
          ((first @*queued*))
          (mapv first @wire) => ["https://api.openai.com/v1/chat/completions"
                                 "https://api.openai.com/v1/chat/completions"]
          (mapv #(get-in % [1 :model]) @wire) => ["gpt-4" "gpt-4"]
          (mapv #(get-in % [1 :max_tokens]) @wire) => [32768 32768]
          (mapv #(get-in % [1 :temperature]) @wire) => [0.9 0.9])))))

(fact "xAI editing has one admitted image start and no fictitious text cap or optimization send"
  (with-runtime
    (fn [_]
      (let [wire (atom []) allowed (update toolset :providers conj
                                           {:name "xai" :models ["grok-4.7" "grok-imagine-image-2.0"]})]
        (hooks/cmd-hook-resolved #"native-edit" #".*"
          (fn [_] (xai/generate-image "edit" ["https://image.invalid/input.png"])
            {:result/value "edited"}))
        (with-redefs [mcp/config (constantly {:servers [] :toolsets [allowed]})
                      xai/config {:key "synthetic-native-key"}
                      http/post (fn [endpoint options]
                                  (swap! wire conj [endpoint (json/parse-string (:body options) true)
                                                   (:ordinal origin/*operation*)])
                                  {:status 200 :body {:data [{:b64_json "AA=="}]}})]
          (dispatch "native-edit")
          (mapv first @wire) => ["https://api.x.ai/v1/images/edits"]
          (get-in @wire [0 1 :n]) => 1
          (contains? (get-in @wire [0 1]) :max_output_tokens) => false
          (get-in @wire [0 2]) => 1)))))

(fact "actual standalone Clairic and router Clef helper sends are metered with their typed schemas, never fake token fields"
  (with-runtime
    (fn [_]
      (let [account (apply str (repeat 32 "a"))
            router {:account-id account :key-file "/synthetic/token"}
            allowed (update toolset :providers conj {:name "cloudflare" :models ["@cf/cloudflare/clairic" "@cf/cloudflare/clef"]})
            wire (atom [])]
        (hooks/cmd-hook-resolved #"typed-source" #".*"
          (fn [_]
            {:result/value (get-in (cloudflare/infer! router "synthetic-key" "clairic"
                                    {:state "source" :fields {:report {:type "text"}}})
                                  [:answers :report :value])}))
        (hooks/cmd-hook-resolved #"classify-source" #".*"
          (fn [_] {:result/value (name (clef/classify! router "ordinary question"))}))
        (with-redefs [mcp/config (constantly {:servers [] :toolsets [allowed]})
                      ai/config (constantly {:providers [provider] :intent-router router})
                      clef/credential! (constantly "synthetic-key")
                      http/post (fn [endpoint options]
                                  (swap! wire conj [endpoint (json/parse-string (:body options) true)
                                                   (execution/admissions execution/*request*)
                                                   (:ordinal origin/*operation*)])
                                  (let [response {:success true :errors []
                                                  :result (if (.endsWith endpoint "/clairic")
                                                            {:model "clairic" :answers {:report {:type "text" :value "typed report"}}}
                                                            {:model "clef" :answers
                                                             {:intent {:type "choice" :choice "answer" :confidence 1
                                                                       :probabilities {:answer 1 :clarify 0 :code_change 0}}
                                                              :explicit_change {:type "noul" :noul 0}}})}]
                                    {:status 200 :body (if (= :json (:as options)) response
                                                        (java.io.ByteArrayInputStream.
                                                          (.getBytes (json/generate-string response) "UTF-8")))}))]
          (dispatch "typed-source")
          (dispatch "classify-source")
          (mapv first @wire) => [(str (cloudflare/account-url account) "/ai/run/@cf/cloudflare/clairic")
                                 (str (cloudflare/account-url account) "/ai/run/@cf/cloudflare/clef")]
          (mapv #(get-in % [2 :admitted]) @wire) => [1 1]
          (mapv #(nth % 3) @wire) => [1 1]
          (mapv #(count (or (get-in % [1 :fields]) (get-in % [1 :questions]))) @wire) => [1 2]
          (every? #(not (contains? (second %) :max_tokens)) @wire) => true)))))

(fact "a derived explicit no-tools stage monotonically narrows the same family before its answer can execute effects"
  (with-runtime
    (fn [_]
      (let [wire (atom []) effects (atom 0)]
        (hooks/cmd-hook-resolved #"closed-write" #".*"
          (fn [_] (swap! effects inc) {:result/value "written"}))
        (with-redefs [http/post (fn [_ options]
                                 (swap! wire conj (:form-params options))
                                 {:status 200 :body
                                  (answer (if (= 1 (count @wire)) "ai --no-tools report" "closed-write"))})]
          (dispatch "ai --max-output-tokens 91 report | split , | xargs %s | xargs %s")
          (count @wire) => 2
          (mapv :max_completion_tokens @wire) => [91 91]
          (empty? (:tools (second @wire))) => true
          @effects => 0)))))

(fact "completed-source executable continuation cannot use a disconnected adapter or revoked current caller"
  (doseq [revocation [:adapter :user]]
    (with-runtime
      (fn [_]
        (let [effects (atom 0) wire (atom 0)]
          (hooks/cmd-hook-resolved #"delayed-write" #".*"
            (fn [_] (swap! effects inc) {:result/value "written"}))
          (with-redefs [http/post (fn [_ _]
                                   (swap! wire inc)
                                   (if (= revocation :adapter) (reset! adapters/adapters {})
                                     (reset! users/users {}))
                                   {:status 200 :body (answer "delayed-write")})]
            (dispatch "ai report | split , | xargs %s")
            @effects => 0
            @wire => 1
            (count @*queued*) => 0))))))

(fact "broader current configuration cannot populate a frozen empty continuation or open MCP discovery"
  (with-runtime
    (fn [_]
      (let [wire (atom []) server {:name "new-server" :url "https://new-server.invalid"}]
        (with-redefs [http/post (fn [_ options]
                                 (swap! wire conj (:form-params options))
                                 {:status 200 :body (answer (if (= 1 (count @wire)) "agent report" "bounded text"))})]
          (dispatch "ai --no-tools report | split , | xargs %s")
          (with-redefs [mcp/config (constantly {:servers [server]
                                              :toolsets [(assoc toolset :native-tools ["command" "cron" "help"]
                                                                :servers [{:name "new-server"}])]})
                        mcp/with-tools (fn [& _] (throw (AssertionError. "Empty ceiling opened MCP")))]
            ((first @*queued*)))
          (count @wire) => 2
          (empty? (:tools (second @wire))) => true)))))

(fact "real inherited agent run dispatches an authorized effect under the same command owner and actual caller"
  (with-runtime
    (fn [_]
      (let [wire (atom []) effects (atom [])]
        (hooks/cmd-hook-resolved #"run-write" #".*"
          (fn [input] (swap! effects conj (select-keys input [:user :yetibot-user]))
            {:result/value "written"}))
        (with-redefs [http/post (fn [_ options]
                                 (swap! wire conj [(:form-params options) (execution/admissions execution/*request*)])
                                 {:status 200 :body (if (= 1 (count @wire)) (tool-call "agent run run-write") (answer "done"))})]
          (dispatch "ai --max-tool-calls 3 work")
          (count @effects) => 1
          (get-in @effects [0 :user :id]) => "U1"
          (get-in @effects [0 :yetibot-user :id]) => "BOT"
          (mapv #(get-in % [1 :admitted]) @wire) => [1 3]
          (count @*queued*) => 0)))))

(fact "nested real agents transfer only the original remaining allocation, never another twenty"
  (with-runtime
    (fn [url]
      (let [wire (atom []) source (atom nil)]
        (with-redefs [http/post (fn [_ options]
                                 (when-not @source (reset! source execution/*request*))
                                 (swap! wire conj (:form-params options))
                                 {:status 200 :body (if (< (count @wire) 3)
                                                     (tool-call "agent continue text") (answer "done"))})]
          (dispatch "ai --max-output-tokens 91 delegate")
          ((first @*queued*))
          (count @*queued*) => 2
          ((second @*queued*))
          (:reserved @(:budget @source)) => 18
          (let [rows (jdbc/query url [(str "SELECT execution_limit,execution_used,execution_reserved,execution_state FROM "
                                           (db/qualified-table-name "agent_run") " ORDER BY id")])]
            (mapv :execution_limit rows) => [18 16]
            (mapv :execution_used rows) => [2 1]
            (mapv :execution_reserved rows) => [16 0]
            (mapv :execution_state rows) => [nil nil])
          (mapv :max_completion_tokens @wire) => [91 91 91])))))

(fact "numbered substitution, intermediary formatting and subexpressions preserve text-only executable lineage"
  (doseq [[expression generated]
          [["ai --no-tools --max-output-tokens 91 report | split , | xargs numbered-text %s" "agent"]
           ["ai --no-tools --max-output-tokens 91 report | echo %s | split , | xargs %s" "agent summarize"]
           ["echo `ai --no-tools --max-output-tokens 91 report` | split , | xargs %s" "agent summarize"]]]
    (with-runtime
      (fn [_]
        (let [wire (atom [])]
          (#'alias/wire-alias {:cmd-name "numbered-text" :cmd "$1 summarize"})
          (with-redefs [http/post (fn [_ options]
                                   (swap! wire conj (:form-params options))
                                   {:status 200 :body (answer (if (= 1 (count @wire)) generated "text answer"))})]
            (dispatch expression)
            (count @*queued*) => 1
            (when (seq @*queued*) ((first @*queued*)))
            (mapv :max_completion_tokens @wire) => [91 91]
            (every? #(empty? (:tools %)) @wire) => true))))))

(fact "explicit short outer timeout closes the real source family; late HTTP completion cannot start an agent or a new default"
  (with-runtime
    (fn [_]
      (let [entered (promise) release (promise) exited (promise) worker-thread (atom nil)
            thread-call async/thread-call state (atom nil) wire (atom 0) legacy (atom 0)]
        (with-redefs [async/thread-call (fn [f]
                                         (thread-call
                                           (fn []
                                             (try (f)
                                                  (finally
                                                    (when (identical? (Thread/currentThread) @worker-thread)
                                                      (deliver exited true)))))))
                      handler/expr-eval-timeout-ms 300
                      core-agent/configured? (constantly false)
                      core-agent/say-unconfigured (fn [] (swap! legacy inc) "legacy")
                      http/post (fn [_ _]
                                  (reset! state execution/*request*)
                                  (reset! worker-thread (Thread/currentThread))
                                  (swap! wire inc) (deliver entered true) @release
                                  {:status 200 :body (answer "agent late")})]
          (let [evaluation (future (dispatch "ai --no-tools report | split , | xargs %s"))]
            (deref entered 3000 false) => true
            (:timeout? (first (deref evaluation 3000 []))) => true
            (:closed? @(:budget @state)) => true
            (deliver release true)
            (deref exited 3000 false) => true
            @wire => 1
            @legacy => 0
            (count @*queued*) => 0))))))

(fact "computed agent action and leading provider controls cannot escape a text-only source"
  (with-runtime
    (fn [_]
      (let [effects (atom 0) wire (atom [])]
        (hooks/cmd-hook-resolved #"closed-write" #".*" (fn [_] (swap! effects inc)))
        (doseq [[generated expression]
                [["run closed-write" "ai --no-tools report | split , | xargs agent %s"]
                 ["--provider other --model other report" "ai --no-tools report | split , | xargs ai %s"]]]
          (reset! wire [])
          (with-redefs [http/post (fn [_ options] (swap! wire conj (:form-params options))
                                   {:status 200 :body (answer generated)})]
            (dispatch expression)
            (count @wire) => 1
            @effects => 0
            (count @*queued*) => 0))))))

(fact "preexisting legacy alias-to-agent and explicit human recurring agent intent retain compatibility without inventing a caller"
  (with-runtime
    (fn [url]
      (let [{:keys [wire command]} (scheduler-functions) tasks (atom {}) legacy (atom 0)]
        (#'alias/wire-alias {:cmd-name "legacy-agent" :cmd "agent compatibility"})
        (hooks/cmd-hook-resolved #"cron" #"(\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+)\s+(.+)" command)
        (with-redefs [scheduler/add-task (fn [_ id task] (swap! tasks assoc id task))
                      scheduler/delete-task (fn [_ id] (swap! tasks dissoc id))
                      core-agent/configured? (constantly false)
                      core-agent/say-unconfigured (fn [] (swap! legacy inc) "legacy")]
          (let [old (first (jdbc/insert! url (db/qualified-table-name "cron")
                            {:chat_source_adapter (pr-str "workspace") :chat_source_room "private"
                             :user_id "U1" :schedule "0 0 8 * * * *" :cmd "legacy-agent"}))]
            (wire (ledger/read-row url "cron" (:id old)))
            ((get-in @tasks [(str "task-" (:id old)) :handler]) nil)
            (:execution-origin (ledger/read-row url "cron" (:id old))) => nil)
          (dispatch "cron 0 0 9 * * * * agent human recurring intent")
          (let [human (last (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "cron") " ORDER BY id")]))]
            (:execution-origin (ledger/read-row url "cron" (:id human))) => "human-literal-v1"
            ((get-in @tasks [(str "task-" (:id human)) :handler]) nil))
          @legacy => 2
          (count @*queued*) => 0
          (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run"))]) => [])))))

(fact "ordinary core and native edits keep the inherited allocation, state and owner sticky"
  (with-runtime
    (fn [url]
      (let [{:keys [command]} (scheduler-functions) tasks (atom {})]
        (hooks/cmd-hook-resolved #"cron" #"(\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+)\s+(.+)" command)
        (hooks/cmd-hook-resolved #"sticky-tick" #".*" (fn [_] {:result/value "done"}))
        (with-redefs [scheduler/add-task (fn [_ id task] (swap! tasks assoc id task))
                      scheduler/delete-task (fn [_ id] (swap! tasks dissoc id))
                      http/post (fn [_ _] {:status 200 :body (tool-call "cron 0 0 8 * * * * sticky-tick")})]
          (dispatch "ai --max-tool-calls 5 schedule")
          (let [id (:id (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "cron"))])))
                tick (get-in @tasks [(str "task-" id) :handler])]
            (tick nil)
            (let [before (ledger/read-row url "cron" id)
                  fields [:execution-origin :execution-limit :execution-used :execution-reserved
                          :generation-cap :execution-state :user-id]
                  snapshot (fn [row] (update (select-keys row fields) :execution-state runs/decode (mcp/config)))]
              (cron/update-where {:id id} {:schedule "0 0 9 * * * *" :user-id "replacement"})
              (snapshot (ledger/read-row url "cron" id)) => (snapshot before)
              (support/in-event support/request "ordinary schedule edit"
                (fn [event]
                  (let [get (native-cron/call! {:toolset toolset} "cron" {"action" "get" "id" id} (:chat-source event))
                        version (get-in get [:structuredContent :item :version])]
                    (native-cron/call! {:toolset toolset} "cron"
                      {"action" "update" "id" id "version" version "schedule" "0 0 10 * * * *"} (:chat-source event)))))
              (snapshot (ledger/read-row url "cron" id)) => (snapshot before)
              (dotimes [_ 3] (tick nil))
              (:execution-used (ledger/read-row url "cron" id)) => 1)))))))

(fact "source event data alone cannot authorize even the first physical send without its current registered adapter and caller"
  (with-runtime
    (fn [_]
      (let [wire (atom 0)]
        (doseq [[connected registry] [[{} @users/users] [@adapters/adapters {}]]]
          (with-redefs [adapters/adapters (atom connected) users/users (atom registry)
                        http/post (fn [& _] (swap! wire inc) {:status 200 :body (answer "not authorized")})]
            (dispatch "ai --no-tools report")
            @wire => 0
            (count @*queued*) => 0))))))

(fact "a revoked registered caller is rejected before MCP discovery, not just before the subsequent model request"
  (with-runtime
    (fn [_]
      (let [discovery (atom 0)
            server {:name "mcp-fixture" :url "https://mcp-fixture.invalid"}]
        (with-redefs [users/users (atom {})
                      mcp/config (constantly {:servers [server]
                                              :toolsets [(assoc toolset :servers [{:name "mcp-fixture"}])]})
                      mcp/with-tools (fn [& _] (swap! discovery inc)
                                       (throw (ex-info "Forbidden discovery boundary reached" {})))]
          (dispatch "ai report")
          @discovery => 0)))))

(fact "original no-tools Responses source preserves identity while removing only native tools"
  (with-runtime
    (fn [_]
      (let [configured (assoc provider :api "responses" :native-tools ["web_search" "x_search"])
            wire (atom [])]
        (with-redefs [ai/config (constantly {:providers [configured]})
                      http/post (fn [endpoint opts]
                                  (swap! wire conj [endpoint (:form-params opts) (:ordinal origin/*operation*)])
                                  {:status 200 :body {:output [{:type "message" :content [{:type "output_text" :text "bounded"}]}]}})]
          (dispatch "ai --no-tools --max-output-tokens 91 hello")
          (count @wire) => 1
          (get-in @wire [0 0]) => "https://fixture.invalid/v1/responses"
          (get-in @wire [0 1 :max_output_tokens]) => 91
          (get-in @wire [0 1 :tools]) => []
          (get-in @wire [0 2]) => 1
          (support/in-event support/request "ai no tools"
            (fn [_]
              (binding [chat/*target* "C1"]
                (execution/with-source* {:provider configured :model "fixture-model" :no-tools true}
                  (fn []
                    (doseq [changed [(assoc configured :native-tools ["web_search"])
                                     (assoc configured :url "https://changed.invalid/v1")
                                     (assoc configured :api "chat-completions")]]
                      (execution/with-source* {:provider changed :model "fixture-model" :no-tools true :single-hop? true}
                        (fn [] (throw (AssertionError. "Widened source reached callback")))) => (throws Exception))
                    (execution/with-source* {:provider (dissoc configured :native-tools)
                                              :model "changed-model" :no-tools true :single-hop? true}
                      (fn [] (throw (AssertionError. "Changed model reached callback")))) => (throws Exception)
                    (with-redefs [mcp/config (constantly {:servers [] :toolsets [(assoc toolset :users ["revoked"])]})]
                      (ai/chat (dissoc configured :native-tools) "fixture-model" [] {}) => (throws Exception))))))))
          (count @wire) => 1
          (support/in-event support/request "ai text-only"
            (fn [_]
              (execution/with-source* {:provider (dissoc configured :native-tools) :model "fixture-model" :no-tools true}
                #(execution/with-source* {:provider configured :model "fixture-model" :no-tools true :single-hop? true}
                   (fn [] (throw (AssertionError. "Native-tool addition reached callback"))))))) => (throws Exception)))))

(fact "genuine HTTP no-tools plaintext uses a finite untrusted family, never chat authority"
  (with-runtime
    (fn [_]
      (let [wire (atom []) effects (atom 0)
            source (pr-str {:adapter :agent :room "agent-room"})
            api-call (fn [command] (http-api/api {:chat-source source :command command} {}))]
        (hooks/cmd-hook-resolved #"http-effect" #".*" (fn [_] (swap! effects inc) {:result/value "effect"}))
        (with-redefs [mcp/config (constantly {:servers []})
                      http/post (fn [endpoint opts]
                                  (swap! wire conj [endpoint (:form-params opts) (:ordinal origin/*operation*)
                                                   (execution/admissions execution/*request*)])
                                  {:status 200 :body (answer "http-effect")})]
          (api-call "ai --no-tools --max-output-tokens 91 hello") => "http-effect"
          (count @wire) => 1
          (get-in @wire [0 1 :max_completion_tokens]) => 91
          (get-in @wire [0 2]) => 1
          (get-in @wire [0 3 :limit]) => 20
          (get-in @wire [0 3 :admitted]) => 1
          (api-call "ai --no-tools hello | xargs %s")
          @effects => 0
          (api-call "ai --no-tools hello | xargs agent %s")
          (count @*queued*) => 0
          (let [before (count @wire)]
            (doseq [command ["ai default fixture" "agent run hello"]]
              (try (api-call command) (catch Exception _ nil)))
            (count @wire) => before
            ;; The supported untrusted plaintext fallback is not a control or
            ;; durable grant, even when the caller omitted --no-tools.
            (api-call "ai hello") => "http-effect"
            (count @wire) => (inc before)
            @effects => 0)
          (let [before @ai/runtime-default]
            (with-redefs [http/post (fn [_ _] {:status 200 :body (answer "ai default fixture")})]
              (api-call "ai --no-tools hello | xargs %s"))
            @ai/runtime-default => before)
          ;; A serialized chat identity does not authenticate an HTTP caller.
          (http-api/api {:chat-source (pr-str (assoc (:chat-source support/request) :room "agent-room"))
                         :command "ai --no-tools hello | xargs %s"} {})
          @effects => 0
          (let [before (count @wire)]
            (with-redefs [mcp/config (constantly {:servers [] :toolsets [toolset]})]
              (api-call "ai --no-tools hello"))
            (count @wire) => before))))))

(fact "actual Grok fallback reconstructs current xai.key transport with shared-other and key-only configuration"
  (with-runtime
    (fn [_]
      (doseq [key-only? [false true]]
        (let [wire (atom [])
              policy (assoc toolset :native-tools [] :providers [{:name "grok" :models ["grok-4.7" "grok-custom"]}])]
          (with-redefs [host-config/get-config (constantly (if key-only? {:error :not-found} {:value {}}))
                        xai/config {:key "SYNTHETIC_XAI_KEY"}
                        ai/config (if key-only? (fn [] (ai/fail "No shared AI configuration"))
                                      (constantly {:providers [provider]}))
                        mcp/config (constantly {:servers [] :toolsets [policy]})
                        http/post (fn [url opts]
                                    (swap! wire conj [url (:form-params opts) (:ordinal origin/*operation*)])
                                    {:status 200 :body {:output [{:type "message" :content [{:type "output_text" :text "fallback answer"}]}]}})]
            (dispatch "grok --max-output-tokens 91 hello")
            (count @wire) => 1
            (get-in @wire [0 0]) => "https://api.x.ai/v1/responses"
            (get-in @wire [0 1 :model]) => "grok-4.7"
            (get-in @wire [0 1 :max_output_tokens]) => 91
            (get-in @wire [0 1 :tools]) => [{:type "web_search"} {:type "x_search"}]
            (get-in @wire [0 2]) => 1
            (dispatch "grok --no-tools --model grok-custom hello")
            (count @wire) => 2
            (get-in @wire [1 1 :model]) => "grok-custom"
            (get-in @wire [1 1 :tools]) => []))))))

(fact "Grok source denies revoked or changed fallback and honors configured-grok precedence"
  (with-runtime
    (fn [_]
      (let [wire (atom [])
            grok-policy (assoc toolset :native-tools [] :providers [{:name "grok" :models ["grok-4.7"]}])]
        (with-redefs [host-config/get-config (constantly {:value {}})
                      xai/config {:key "SYNTHETIC_XAI_KEY"}
                      mcp/config (constantly {:servers [] :toolsets [grok-policy]})
                      http/post (fn [url _] (swap! wire conj url)
                                  {:status 200 :body (answer "configured")})]
          (let [fallback (ai/provider (ai/grok-config) "grok")]
            (support/in-event support/request "grok hello"
              (fn [_]
                (binding [chat/*target* "C1"]
                  (execution/with-source* {:provider fallback :model "grok-4.7" :no-tools true}
                    (fn []
                      (with-redefs [xai/config {}]
                        (ai/chat (dissoc fallback :native-tools) "grok-4.7" [] {}) => (throws Exception))
                      (doseq [changed [(assoc fallback :url "https://changed.invalid/v1")
                                       (assoc fallback :native-tools ["web_search"])
                                       (assoc fallback :api "chat-completions" :native-tools [])]]
                        (with-redefs [ai/config (constantly {:providers [changed]})]
                          (ai/chat (dissoc fallback :native-tools) "grok-4.7" [] {}) => (throws Exception)))))))))
          @wire => []
          (with-redefs [ai/config (constantly {:providers [(assoc provider :name "grok" :model "grok-4.7")]})]
            (dispatch "grok --no-tools hello"))
          @wire => ["https://fixture.invalid/v1/chat/completions"]
          (with-redefs [host-config/get-config (constantly {:error :invalid})
                        ai/config (fn [] (ai/fail "AI configuration is invalid."))]
            (dispatch "grok hello"))
          (count @wire) => 1)))))

(fact "scheduled Slack logging and Discord partial delivery retain the enclosing fence and stop all future callbacks"
  (with-runtime
    (fn [url]
      (doseq [mode [:slack :discord]]
        (let [{:keys [wire command]} (scheduler-functions)
              tasks (atom {}) effects (atom 0) parts (atom []) observed (atom [])
              request (if (= mode :slack) support/request
                        {:user (:user support/request)
                         :chat-source {:adapter :discord :uuid "workspace" :room "private"
                                       :raw-event {:author {:id "U1"} :channel-id "C1" :id "message"}}})
              adapter (if (= mode :slack)
                        (slack/map->Slack {:config {:name "workspace"} :connected? (atom true)
                                          :connection-last-active-timestamp (atom (System/currentTimeMillis))})
                        (discord/map->Discord {:config {:name "workspace"} :connected? (atom true)
                                              :conn (atom {:rest :scripted}) :yetibot-user (atom {:id "BOT"})}))
              record-part (fn [target body]
                            (let [row (first (jdbc/query url [(str "SELECT * FROM " (db/qualified-table-name "cron")
                                                                   " ORDER BY id DESC LIMIT 1")]
                                                       {:identifiers #(clojure.string/replace % "_" "-")}))]
                              (swap! observed conj [(:execution-phase row)
                                                    (:phase (runs/decode (:execution-state row) {}))
                                                    (:state (first (json/parse-string (:operation-receipts row) true)))])
                              (swap! parts conj [target body])))]
          (hooks/cmd-hook-resolved #"cron" #"(\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+)\s+(.+)" command)
          (hooks/cmd-hook-resolved #"delivery-tick" #".*"
            (fn [_] (swap! effects inc)
              {:result/value (if (= mode :slack) "scheduled answer"
                               "two artifacts https://bot.invalid/generated-images/one.png\nhttps://bot.invalid/generated-images/two.png")}))
          (with-redefs [adapters/adapters (atom {"workspace" adapter})
                        users/users (atom {{:adapter mode :id "U1"} (:user request)
                                           {:adapter mode :id "BOT"} {:id "BOT"}})
                        mcp/config (constantly {:servers [] :toolsets [(assoc toolset :adapters [(name mode)])]})
                        scheduler/add-task (fn [_ id task] (swap! tasks assoc id task))
                        scheduler/delete-task (fn [_ id] (swap! tasks dissoc id))
                        http/post (fn [_ _] {:status 200 :body (tool-call "cron 0 0 8 * * * * delivery-tick")})
                        slack-chat/post-message (fn [_ target body _] (record-part target body) {:ok true :ts "scripted"})
                        images/image-store (atom {"one" {:data "AA==" :mime-type "image/png"}
                                                  "two" {:data "AA==" :mime-type "image/png"}})]
            (with-redefs-fn
              {#'discord/create-message!
               (fn [_ target part]
                 (record-part target part)
                 (if (= 1 (count @parts)) {:id "first-part"}
                   (throw (Exception. "scripted second-part failure"))))}
              (fn []
                (support/in-event request "ai --max-tool-calls 5 schedule"
                  (fn [{:keys [chat-source user]}]
                    (binding [interpreter/*chat-source* chat-source chat/*target* "C1" chat/*adapter* adapter]
                      (handler/record-and-run-raw "!ai --max-tool-calls 5 schedule" user {:id "BOT"}
                                                  {:record-yetibot-response? false}))))
                (let [row (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "cron") " ORDER BY id DESC LIMIT 1")]))
                      id (:id row) callback (get-in @tasks [(str "task-" id) :handler])]
                  (callback nil)
                  @effects => 1
                  (count @parts) => (if (= mode :slack) 1 2)
                  (set @observed) => #{["operation-in-flight" :cron-delivery-started "intent"]}
                  (let [after (ledger/read-row url "cron" id)]
                    (:execution-phase after) => "unknown"
                    (:execution-used after) => 1
                    (:execution-reserved after) => 0
                    (wire after)
                    (dotimes [_ 3]
                      (callback nil)
                      ((get-in @tasks [(str "task-" id) :handler]) nil))
                    @effects => 1
                    (count @parts) => (if (= mode :slack) 1 2)
                    (select-keys (ledger/read-row url "cron" id)
                                [:execution-used :execution-reserved :worker-fence])
                    => (select-keys after [:execution-used :execution-reserved :worker-fence])))))))))))

(fact "genuine overlapping events exclude a physically blocked initial worker until its one completion"
  (with-runtime
    (fn [url]
      (let [wire (atom 0) entered (promise) release (promise) sent (atom [])]
        (binding [*deliveries* sent]
          (with-redefs [http/post (fn [_ _]
                                   (let [n (swap! wire inc)]
                                     (when (= 2 n) (deliver entered true) @release)
                                     {:status 200 :body (answer (if (= 1 n) "agent report" "completed once"))}))]
            (dispatch "ai --no-tools report | split , | xargs %s")
            (let [id (:id (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run"))])))]
              (count @*queued*) => 1
              (let [worker (future ((first @*queued*)))]
                (try
                  (deref entered 5000 false) => true
                  (let [before (ledger/read-row url "agent_run" id)
                        events (doall (repeatedly 2 #(future (support/in-event support/request "echo overlap" (fn [_] nil)))))]
                    (doseq [event events] (deref event 5000 :timeout) =not=> :timeout)
                    (count @*queued*) => 1
                    (doseq [unexpected (rest @*queued*)] (unexpected))
                    (:execution-phase (ledger/read-row url "agent_run" id)) => "operation-in-flight"
                    (:worker-fence (ledger/read-row url "agent_run" id)) => (:worker-fence before))
                  (finally (deliver release true)))
                (try (deref worker 5000 :timeout) (catch Exception _ :worker-error))
                => {:status :unconfirmed :work-outcome :completed}
                @wire => 2
                (count @sent) => 1
                (:execution-phase (ledger/read-row url "agent_run" id)) => "terminal"
                (support/in-event support/request "echo after completion" (fn [_] nil))
                (count @*queued*) => 1))))))))

(fact "a scheduler enqueue-then-throw releases only queued ownership and invalidates the stale callback"
  (with-runtime
    (fn [url]
      (let [wire (atom 0)]
        (reset! *schedule-failure* :after-enqueue)
        (with-redefs [http/post (fn [_ _] (swap! wire inc)
                                 {:status 200 :body (answer (if (= 1 @wire) "agent report" "done"))})]
          (dispatch "ai --no-tools report | split , | xargs %s")
          (count @*queued*) => 1
          (let [id (:id (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run"))])))
                allocated (:execution-limit (ledger/read-row url "agent_run" id))]
            (reset! *schedule-failure* false)
            (support/in-event support/request "echo recovery" (fn [_] nil))
            (count @*queued*) => 2
            ((first @*queued*)) => {:status :not-owner}
            @wire => 1
            ((second @*queued*)) => {:status :unconfirmed :work-outcome :completed}
            @wire => 2
            (:execution-limit (ledger/read-row url "agent_run" id)) => allocated
            (:execution-phase (ledger/read-row url "agent_run" id)) => "terminal"
            (count (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run"))])) => 1))))))

(fact "genuine events do not replace a registered initial worker that is still queued"
  (with-runtime
    (fn [_]
      (with-redefs [http/post (fn [_ _] {:status 200 :body (answer "agent report")})]
        (dispatch "ai --no-tools report | split , | xargs %s")
        (dotimes [_ 3] (support/in-event support/request "echo queued" (fn [_] nil)))
        (count @*queued*) => 1))))

(fact "a failed physical worker send releases local ownership but restart recovery never repeats unknown IO"
  (with-runtime
    (fn [url]
      (let [wire (atom 0)]
        (with-redefs [http/post (fn [_ _]
                                 (if (= 1 (swap! wire inc))
                                   {:status 200 :body (answer "agent report")}
                                   (throw (Exception. "scripted uncertain physical send"))))]
          (dispatch "ai --no-tools report | split , | xargs %s")
          ((first @*queued*)) => (throws Exception)
          @wire => 2
          (support/in-event support/request "echo restart recovery" (fn [_] nil))
          (count @*queued*) => 1
          (support/in-event support/request "echo no replay" (fn [_] nil))
          (count @*queued*) => 1
          @wire => 2
          (let [id (:id (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run"))])))
                row (ledger/read-row url "agent_run" id)]
            (:execution-phase row) => "unknown"
            (runs/claim! url "agent_run" id (:state-revision row) {}) => {:status :unknown}))))))

(fact "HTTP configured Responses native planner composes with original no-tools and plaintext defaults"
  (with-runtime
    (fn [_]
      (let [configured (assoc provider :api "responses" :native-tools ["web_search" "x_search"])
            wire (atom [])
            call! #(http-api/api {:chat-source (pr-str {:adapter :agent :room "agent-room"})
                                  :command %} {})]
        (with-redefs [ai/config (constantly {:providers [configured]})
                      mcp/config (constantly {:servers []})
                      http/post (fn [endpoint options]
                                  (swap! wire conj [endpoint (:form-params options)
                                                   (:ordinal origin/*operation*)
                                                   (execution/admissions execution/*request*)])
                                  {:status 200 :body {:status "completed" :output [{:type "message" :content [{:type "output_text" :text "bounded"}]}]}})]
          (call! "ai --no-tools --max-output-tokens 91 hello") => "bounded"
          (count @wire) => 1
          (get-in @wire [0 1 :tools]) => []
          (get-in @wire [0 1 :max_output_tokens]) => 91
          (get-in @wire [0 2]) => 1
          (get-in @wire [0 3 :limit]) => 20
          (call! "ai --max-output-tokens 91 hello") => "bounded"
          (count @wire) => 2
          (get-in @wire [1 1 :tools]) => [{:type "web_search"} {:type "x_search"}]
          (with-redefs [mcp/config (constantly {:servers [] :toolsets [toolset]})]
            (call! "ai --no-tools hello"))
          (count @wire) => 2
          (call! "ai --provider http://127.0.0.1:9/private --no-tools hello")
          (count @wire) => 2
          (execution/with-source* {:provider configured :model "fixture-model" :no-tools true}
            (fn []
              (:native-tools (:planner-provider execution/*request*)) => ["web_search" "x_search"]
              (:untrusted-text? execution/*request*) => true
              (:no-host-execution? execution/*request*) => true
              execution/*event* => nil
              (origin/invoke! origin/*context* :start {:match ["agent run work" "work"]}) => (throws Exception)
              (doseq [[changed model] [[(assoc configured :url "http://127.0.0.1:9/private") "fixture-model"]
                                       [(assoc configured :api "chat-completions") "fixture-model"]
                                       [(assoc configured :native-tools ["web_search"]) "fixture-model"]
                                       [(dissoc configured :native-tools) "changed-model"]]]
                (ai/chat changed model [] {}) => (throws Exception))
              (doseq [changed [(assoc configured :url "http://127.0.0.1:9/private")
                               (assoc configured :native-tools ["web_search"])]]
                (with-redefs [ai/config (constantly {:providers [changed]})]
                  (ai/chat (dissoc configured :native-tools) "fixture-model" [] {}) => (throws Exception)))
              (with-redefs [ai/config (constantly {:providers []})]
                (ai/chat (dissoc configured :native-tools) "fixture-model" [] {}) => (throws Exception))
              (with-redefs [mcp/config (constantly {:servers [] :toolsets [toolset]})]
                (ai/chat (dissoc configured :native-tools) "fixture-model" [] {}) => (throws Exception))))
          (count @wire) => 2
          (count @*queued*) => 0)))))

(fact "generated native OpenAI and xAI ai turns retain actual identity and honor explicit narrower caps and no-tools at admission and wire"
  (doseq [kind [:openai :xai] explicit-no-tools? [false true]]
    (with-runtime
      (fn [_]
        (let [wire (atom []) admissions (atom []) capture usage/capture!
              native-name (if (= kind :openai) "openai" "xai")
              native-model (if (= kind :openai) "gpt-4" "grok-4.7")
              endpoint (if (= kind :openai) "https://api.openai.com/v1/chat/completions" "https://api.x.ai/v1/responses")
              generated (str "ai " (when explicit-no-tools? "--no-tools ") "--max-output-tokens 91 report")]
          (hooks/cmd-hook-resolved #"native-options-text" #".*"
            (fn [_] {:result/value (:text (xai/generate-text "source prompt"))}))
          (with-redefs [mcp/config (constantly {:servers [] :toolsets [(update toolset :providers conj
                                                                       {:name native-name :models [native-model]})]})
                        openai/config {:key "synthetic-native-key"}
                        xai/config {:key "synthetic-native-key"}
                        usage/capture! (fn [attrs request project]
                                         (swap! admissions conj attrs) (capture attrs request project))
                        http/post (fn [url options]
                                    (let [body (or (:form-params options) (json/parse-string (:body options) true))]
                                      (swap! wire conj {:url url :body body :ordinal (:ordinal origin/*operation*)
                                                        :family (:family-id execution/*request*) :headers (:headers options)})
                                      {:status 200 :body
                                       (let [text (if (= 1 (count @wire)) generated "native bounded answer")]
                                         (if (= kind :openai) (answer text)
                                           {:output [{:type "message" :content [{:text text}]}]}))}))]
            (dispatch (str (if (= kind :openai) "openai report" "native-options-text") " | split , | xargs %s"))
            (count @wire) => 2
            (mapv :url @wire) => [endpoint endpoint]
            (mapv #(get-in % [:body :model]) @wire) => [native-model native-model]
            (mapv #(get-in % [:body (if (= kind :openai) :max_tokens :max_output_tokens)]) @wire) => [32768 91]
            (mapv #(let [cap (:output-cap %)] (if (map? cap) (:value cap) cap)) @admissions) => [32768 91]
            (every? #(and (integer? (:ordinal %)) (pos? (:ordinal %))) @wire) => true
            (count (distinct (map :family @wire))) => 1
            (get-in @wire [1 :body :tools]) => (when (and (= kind :xai) (not explicit-no-tools?))
                                               [{:type "web_search"} {:type "x_search"}])
            (get-in @wire [0 :body :tools]) => (when (= kind :xai) [{:type "web_search"} {:type "x_search"}])
            (every? #(some #{"Bearer synthetic-native-key"} (vals (:headers %))) @wire) => true
            (count @*queued*) => 0))))))

(fact "scaled stalled native OpenAI continuation times out releases the actual local worker and leaves unknown nonreplayable admission"
  (with-runtime
    (fn [url]
      (let [posts (atom []) accepted (promise) release (promise)
            physical-post http/post physical-error (atom nil) owner-key (atom nil) worker (atom nil)]
        (scripted/with-http-server
          (fn [_]
            {:stream (fn [out]
                       (let [body (.getBytes (json/generate-string (answer "late answer")) "UTF-8")]
                         (.write out body 0 5) (.flush out)
                         (deliver accepted true)
                         (deref release 5000 nil)
                         (try (.write out body 5 (- (alength body) 5)) (.flush out)
                              (catch java.io.IOException _ nil))))})
          (fn [base]
            (with-redefs [mcp/config (constantly {:servers [] :toolsets [(update toolset :providers conj
                                                                       {:name "openai" :models ["gpt-4"]})]})
                          openai/config {:key "synthetic-stall-key"}
                          http/post (fn [endpoint options]
                                      (swap! posts conj [endpoint options])
                                      (if (= 1 (count @posts))
                                        {:status 200 :body (answer "agent report")}
                                        (do
                                          ;; Assert real production values separately. Only fixture waits scale.
                                          (fact (:conn-timeout options) => 5000
                                                (:socket-timeout options) => 120000
                                                (:redirect-strategy options) => :none
                                                ((:retry-handler options) nil nil nil) => false)
                                          (try
                                            (physical-post (str base "/stalled")
                                              (cond-> options
                                                (:conn-timeout options) (assoc :conn-timeout 100)
                                                (:socket-timeout options) (assoc :socket-timeout 100)))
                                            (catch Exception e (reset! physical-error e) (throw e))))))]
              (dispatch "openai report | split , | xargs %s")
              (let [id (:id (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run"))])))
                    key [url (db/qualified-table-name "agent_run") id]]
                (reset! owner-key key)
                (get-in @@#'agent/live-workers [key :phase]) => :queued
                (reset! worker (future (try ((first @*queued*)) (catch Exception e {:error e}))))
              (try
                (deref accepted 3000 false) => true
                (let [finished (deref @worker 1000 :still-running)]
                  (instance? Throwable (:error (when (map? finished) finished))) => true
                  (instance? java.net.SocketTimeoutException @physical-error) => true
                  (println "STALLED-PHYSICAL-ERROR" (some-> @physical-error class .getName))
                  (contains? @@#'agent/live-workers @owner-key) => false
                  (let [id (last @owner-key) row (ledger/read-row url "agent_run" id)]
                    (:execution-phase row) => "unknown"
                    (runs/claim! url "agent_run" id (:state-revision row) {}) => {:status :unknown}
                    (some #(= "unknown" (:state %)) (json/parse-string (:operation-receipts row) true)) => true)
                  (support/in-event support/request "echo recover stalled" (fn [_] nil))
                  (support/in-event support/request "echo recover again" (fn [_] nil))
                  (count @*queued*) => 1
                  (count @posts) => 2
                  (println "SCALED-TIMEOUT-EVIDENCE fixture connect/socket=100ms; asserted production connect=5000ms socket=120000ms; no measured120s claim"))
                (finally
                  (deliver release true)
                  (deref @worker 3000 :fixture-worker-did-not-exit)))))))))))

(fact "revoked current policy blocks generated native continuation before any second admission or physical send"
  (doseq [kind [:openai :xai]]
    (with-runtime
      (fn [_]
        (let [native-name (if (= kind :openai) "openai" "xai")
              native-model (if (= kind :openai) "gpt-4" "grok-4.7")
              policy (atom {:servers [] :toolsets [(update toolset :providers conj
                                                                    {:name native-name :models [native-model]})]})
              wire (atom 0) admissions (atom 0) capture usage/capture!]
          (hooks/cmd-hook-resolved #"native-denied-text" #".*"
            (fn [_] {:result/value (:text (xai/generate-text "source prompt"))}))
          (with-redefs [mcp/config #(deref policy)
                        openai/config {:key "synthetic-native-key"}
                        xai/config {:key "synthetic-native-key"}
                        usage/capture! (fn [& args] (swap! admissions inc) (apply capture args))
                        http/post (fn [_ _]
                                    (swap! wire inc)
                                    (swap! policy assoc-in [:toolsets 0 :users] ["revoked"])
                                    {:status 200 :body
                                     (if (= kind :openai) (answer "ai --no-tools --max-output-tokens 91 report")
                                       {:output [{:type "message" :content [{:text "ai --no-tools --max-output-tokens 91 report"}]}]})})]
            (dispatch (str (if (= kind :openai) "openai report" "native-denied-text") " | split , | xargs %s"))
            @wire => 1
            @admissions => 1
            (count @*queued*) => 0))))))

(fact "generated native xAI continuation checks actual nonempty incomplete and failed Responses outcomes before normalization"
  (doseq [[status reason expected] [["incomplete" "max_output_tokens" :output-limit]
                                     ["incomplete" "other" :incomplete-output]
                                     ["failed" nil :incomplete-output]
                                     ["completed" nil nil]]]
    (with-runtime
      (fn [_]
        (let [wire (atom []) observed (atom []) dispatched (atom nil) original ai/native-xai-text]
          (hooks/cmd-hook-resolved #"native-outcome-text" #".*"
            (fn [_] {:result/value (:text (xai/generate-text "source prompt"))}))
          (with-redefs [mcp/config (constantly {:servers [] :toolsets [(update toolset :providers conj
                                                                      {:name "xai" :models ["grok-4.7"]})]})
                        xai/config {:key "synthetic-outcome-key"}
                        ai/native-xai-text (fn [messages options]
                                             (try (let [r (original messages options)]
                                                    (swap! observed conj {:body r}) r)
                                                  (catch Exception e
                                                    (swap! observed conj (ex-data e)) (throw e))))
                        http/post (fn [url options]
                                    (swap! wire conj [url options (json/parse-string (:body options) true)
                                                      (:family-id execution/*request*)])
                                    {:status 200 :body
                                     (if (= 1 (count @wire))
                                       {:output [{:type "message" :content [{:text "ai --no-tools --max-output-tokens 91 report"}]}]}
                                       {:status status :incomplete_details {:reason reason}
                                        :usage {:input_tokens 7 :output_tokens 91}
                                        :output [{:type "message" :content [{:type "output_text" :text "nonempty partial answer"}]}]})})]
            (reset! dispatched (dispatch "native-outcome-text | split , | xargs %s"))
            (count @wire) => 2
            (mapv first @wire) => ["https://api.x.ai/v1/responses" "https://api.x.ai/v1/responses"]
            (get-in @wire [1 2 :model]) => "grok-4.7"
            (get-in @wire [1 2 :max_output_tokens]) => 91
            (get-in @wire [1 2 :tools]) => nil
            (get-in @wire [0 2 :tools]) => [{:type "web_search"} {:type "x_search"}]
            (count (distinct (map last @wire))) => 1
            (select-keys (get-in @wire [1 1]) [:conn-timeout :socket-timeout :redirect-strategy])
            => {:conn-timeout 5000 :socket-timeout 120000 :redirect-strategy :none}
            ((get-in @wire [1 1 :retry-handler]) nil nil nil) => false
            (:ai/output-error (last @observed)) => expected
            (when expected (boolean (re-find #"nonempty partial answer" (pr-str @dispatched))))
            => (when expected false)
            (when-not expected (select-keys (:body (last @observed)) [:status :usage]))
            => (when-not expected {:status "completed" :usage {:input_tokens 7 :output_tokens 91}})
            (when expected
              (select-keys (:ai/response (last @observed))
                           [:provider :model :endpoint :status :output-cap :text-present :input_count :output_count]))
            => (when expected {:provider :grok :model :grok :endpoint :responses :status (keyword status)
                               :output-cap 91 :text-present true :input_count 7 :output_count 91})
            (count @*queued*) => 0))))))

(fact "nested agent run preserves uncertain effect in the actual live and durable command owner without replay"
  (doseq [durable? [false true]]
    (with-runtime
      (fn [url]
        (let [wire (atom []) effects (atom 0) later (atom 0) families (atom []) results (atom [])
              complete tools/complete]
          (hooks/cmd-hook-resolved #"uncertain-run-write" #".*"
            (fn [_] (swap! effects inc) (throw (Exception. "effect performed before receipt"))))
          (hooks/cmd-hook-resolved #"after-uncertain-run" #".*"
            (fn [_] (swap! later inc) {:result/value "must not run"}))
          (with-redefs [tools/complete (fn [& args]
                                                   (let [r (apply complete args)] (swap! results conj r) r))
                        http/post (fn [_ options]
                                    (swap! families conj execution/*request*)
                                    (let [n (count (swap! wire conj (:form-params options)))]
                                      {:status 200 :body
                                       (if (and durable? (= n 1)) (tool-call "agent nested work")
                                         (if (= n (if durable? 2 1))
                                           (update-in (tool-call "agent run uncertain-run-write") [:choices 0 :message :tool_calls]
                                             conj {:id "later" :type "function" :function {:name "yetibot_command"
                                                                                          :arguments "{\"command\":\"after-uncertain-run\"}"}})
                                           (answer "must not infer after unknown")))}))]
            (dispatch "ai nested uncertain work")
            (when durable? (try ((first @*queued*)) (catch Exception _ nil))))
          @effects => 1
          @later => 0
          (count @wire) => (if durable? 2 1)
          (get-in (last @results) [:result/data :execution :reason]) => :unknown-result
          (mapv :outcome (get-in (last @results) [:result/data :execution :calls])) => [:unknown :skipped]
          (:unknown? @(:budget (last @families))) => true
          (when durable?
            (let [id (:id (first (jdbc/query url [(str "SELECT id FROM " (db/qualified-table-name "agent_run"))])))
                  row (ledger/read-row url "agent_run" id)]
              (:execution-phase row) => "unknown"
              (mapv :state (json/parse-string (:operation-receipts row) true)) => ["definitive" "unknown"]
              (runs/claim! url "agent_run" id (:state-revision row) {}) => {:status :unknown}
              (dotimes [_ 2] (support/in-event support/request "echo recover uncertain run" (fn [_] nil)))
              (count @*queued*) => 1
              (count @wire) => 2
              @effects => 1)))))))

(fact "nested agent run known command error stays definitive and success remains available"
  (with-runtime
    (fn [_]
      (let [wire (atom 0) effects (atom 0) results (atom []) complete ai-command/complete-prompt]
        (hooks/cmd-hook-resolved #"known-run-denial" #".*" (fn [_] (swap! effects inc) {:result/value "must not run"}))
        (with-redefs [admin/admin-only-command? #(= "known-run-denial" %)
                      admin/user-is-admin? (constantly false)
                      ai-command/complete-prompt (fn [p] (let [r (complete p)] (swap! results conj r) r))
                      http/post (fn [_ _] {:status 200 :body (if (= 1 (swap! wire inc))
                                                                            (tool-call "agent run known-run-denial") (answer "known error"))})]
          (dispatch "ai --max-tool-calls 3 known error"))
        @effects => 0
        @wire => 2
        (get-in (last @results) [:result/data :execution :reason]) => :partial-result
        (mapv :outcome (get-in (last @results) [:result/data :execution :calls])) => [:error]))))

(fact "first native gi optimizer validates failed and incomplete nonempty physical Responses before any image or artifact"
  (doseq [[status reason expected] [["failed" nil :incomplete-output]
                                   ["incomplete" "max_output_tokens" :output-limit]
                                   ["completed" nil nil]]]
    (with-runtime
      (fn [_]
        (let [wire (atom []) publications (atom []) observed (atom []) generate xai/generate-text]
          (require 'yetibot.core.commands.grokimage)
          (hooks/cmd-hook-resolved #"gi" #".*" (deref (ns-resolve 'yetibot.core.commands.grokimage 'grokimage-cmd)))
          (with-redefs [mcp/config (constantly {:servers [] :toolsets [(update toolset :providers conj
                                                                       {:name "xai" :models ["grok-4.7" "grok-imagine-image-2.0"]})]})
                        xai/config {:key "safe-first-optimizer-key"}
                        xai/generate-text (fn [prompt]
                                            (try (let [r (generate prompt)] (swap! observed conj r) r)
                                                 (catch Exception e (swap! observed conj (ex-data e)) (throw e))))
                        images/store-image! (fn [& args] (swap! publications conj args) "https://bot.invalid/image.jpg")
                        http/post (fn [endpoint opts]
                                    (swap! wire conj [endpoint opts (when (:body opts) (json/parse-string (:body opts) true))])
                                    {:status 200 :body
                                     (cond
                                       (.endsWith endpoint "/chat/completions") (if (= 1 (count @wire)) (tool-call "gi safe scene") (answer "finished"))
                                       (.endsWith endpoint "/responses")
                                       {:status status :incomplete_details {:reason reason}
                                        :usage {:input_tokens 7 :output_tokens 91}
                                        :output [{:type "reasoning" :text "original reasoning"}
                                                 {:type "message" :content [{:type "output_text" :text "nonempty optimizer text"}]}]}
                                       :else {:data [{:b64_json "AA=="}]})})]
            (dispatch "ai --max-output-tokens 91 image"))
          (let [native (filter #(.startsWith (first %) "https://api.x.ai/") @wire)]
            (println "FIRST-OPTIMIZER-CAUSAL" status
                     {:native-endpoints (mapv first native) :artifact-publications (count @publications)})
            (mapv first native) => (if expected ["https://api.x.ai/v1/responses"]
                                                     ["https://api.x.ai/v1/responses" "https://api.x.ai/v1/images/generations"])
            (count @wire) => (if expected 3 4)
            (set (keys (get-in (first native) [2]))) => #{:model :input :max_output_tokens :tools}
            (get-in (first native) [1 :headers "Authorization"]) => "Bearer safe-first-optimizer-key"
            (get-in (first native) [2 :max_output_tokens]) => 91
            (get-in (first native) [2 :tools]) => [{:type "web_search"} {:type "x_search"}]
            (select-keys (second (first native)) [:conn-timeout :socket-timeout :redirect-strategy])
            => {:conn-timeout 5000 :socket-timeout 120000 :redirect-strategy :none}
            ((get-in (first native) [1 :retry-handler]) nil nil nil) => false
            (:ai/output-error (first @observed)) => expected
            (when-not expected (first @observed)) => (when-not expected {:text "nonempty optimizer text" :reasoning "original reasoning" :cost 0.00056})
            (count @publications) => (if expected 0 1)
            (when expected (select-keys (:ai/response (first @observed)) [:status :output-cap :input_count :output_count]))
            => (when expected {:status (keyword status) :output-cap 91 :input_count 7 :output_count 91})))))))

(fact "native first-hop input output reasoning and legacy cost shapes survive the actual source boundary with one usage projection"
  (doseq [[output expected] [[{:type "message" :content "plain text"} {:text "plain text"}]
                             [{:type "message" :message {:content [{:text "nested "} {:text "text"}]}} {:text "nested text"}]
                             [{:type "message" :text "top-level text"} {:text "top-level text"}]
                             [{:type "reasoning" :reasoning {:text "reasoning only"}} {:reasoning "reasoning only"}]]
          prompt ["source prompt" [{:role "user" :content "supplied native messages"}]]]
    (with-runtime
      (fn [url]
        (database/idempotent-create-table! (:schema/table usage-store/schema) (:schema/specs usage-store/schema))
        (try
         (let [wire (atom []) normalized (atom nil) projections (atom 0) metrics usage/xai-metrics]
          (hooks/cmd-hook-resolved #"native-shape" #".*"
            (fn [_] (reset! normalized (xai/generate-text prompt)) {:result/value "received"}))
          (with-redefs [database/connected? (atom true)
                        mcp/config (constantly {:servers [] :toolsets [(update toolset :providers conj
                                                                       {:name "xai" :models ["grok-4.7"]})]})
                        xai/config {:key "safe-shape-key"}
                        usage/xai-metrics (fn [model response] (swap! projections inc) (metrics model response))
                        http/post (fn [endpoint options]
                                    (swap! wire conj [endpoint (or (:form-params options) (json/parse-string (:body options) true))])
                                    {:status 200 :body (if (.endsWith endpoint "/responses")
                                                        {:usage {:prompt_tokens 7 :input_tokens 99 :completion_tokens 3 :output_tokens 91}
                                                         :output [output]}
                                                        (if (= 1 (count @wire)) (tool-call "native-shape") (answer "done")))})]
            (dispatch "ai --max-output-tokens 91 native shape"))
          @normalized => (assoc expected :cost 0.000032)
          @projections => 1
          (let [events (jdbc/query url [(str "SELECT provider,model_usage FROM " (db/qualified-table-name "ai_usage")
                                             " WHERE provider='xai'")])
                row (first (json/parse-string (str (:model_usage (first events))) true))]
            (count events) => 1
            (select-keys row [:model :requests :input_tokens :output_tokens :usd :cost_kind])
            => {:model "grok-4.7" :requests 1 :input_tokens 99 :output_tokens 91 :usd 0.000744 :cost_kind "estimate"})
          (count @wire) => 3
          (get-in @wire [1 1 :max_output_tokens]) => 91
          (get-in @wire [1 1 :input]) => (if (string? prompt) [{:role "user" :content prompt}] prompt)
          (get-in @wire [1 1 :tools]) => [{:type "web_search"} {:type "x_search"}])
         (finally (jdbc/db-do-commands url (str "DROP TABLE " (db/qualified-table-name "ai_usage")))))))))

(fact "last admitted checkpoints finalize only definitive completed work under current authority"
  (doseq [kind [:text :chart :pending]
          recovery (if (= kind :pending) [:success]
                     (if (= kind :chart) [:success :revoked-user :changed-provider :expired :revoked-chart]
                       [:success :revoked-user :changed-provider]))]
    (with-runtime
      (fn [url]
        (let [wire (atom []) collections (atom 0) renders (atom 0) deliveries (atom [])
              committed (atom nil) saved (atom nil) finalized (atom nil) captured-context (atom nil)
              checkpoint runs/checkpoint! save-result runs/save-result! finalize tools/finalize-checkpoint
              priced-provider (assoc provider :url "https://api.x.ai/v1" :model "grok-4.7")
              artifact {:type "vegalite" :format "png" :mime-type "image/png"
                        :bytes 64 :id "last-chart" :ephemeral true
                        :url "https://bot.invalid/generated-images/last-chart.png"}
              policy (assoc toolset :native-tools ["command" "cron" "chart"]
                                   :providers [{:name "fixture" :models ["grok-4.7"]}])
              read-row #(ledger/read-row url "agent_run" (:id %))]
          (with-redefs [mcp/config (constantly {:servers [] :toolsets [policy]})
                        ai/config (constantly {:providers [priced-provider]})
                        images/image-store (atom {"last-chart" {:data "AA==" :mime-type "image/png"}})
                        gemini/yetibot-base-url (constantly "https://bot.invalid")
                        http/post
                        (fn [_ opts]
                          (reset! captured-context origin/*context*)
                          (let [form (:form-params opts) n (count (swap! wire conj form))]
                            {:status 200 :body
                             (assoc
                               (cond
                                 (= n 1) (tool-call "agent complete the requested answer")
                                 (= kind :text) (answer "The already completed final answer.")
                                 (= kind :pending) (tool-call "must-not-run")
                                 (= n 2) (tool-call "last-chart-rows")
                                 :else (let [d (->> (:messages form) (filter #(= "tool" (:role %)))
                                                   (keep #(get-in (json/parse-string (:content %) true)
                                                                  [:structuredContent :datasets 0])) last)]
                                         {:choices [{:message {:role "assistant" :content nil
                                                               :tool_calls [{:id "last-render" :type "function"
                                                                             :function {:name "yetibot_chart"
                                                                                        :arguments (json/generate-string
                                                                                                     {:dataset_id (:dataset-id d) :kind "bar" :x "label" :y "reading"})}}]}}]}))
                               :usage {:prompt_tokens 7 :completion_tokens 3})}))
                        kroki/client-call (fn [op request]
                                            (if (= op "validate-render!") request
                                              (do (swap! renders inc)
                                                  (public/reviewed {:result/value (:url artifact) :result/data artifact
                                                                    :result/public-data artifact}))))]
            (hooks/cmd-hook-resolved #"last-chart-rows" #".*"
              (fn [_] (swap! collections inc)
                {:result/value "complete readings" :result/data [{:label "A" :reading 2} {:label "B" :reading 3}]}))
            (binding [*deliveries* deliveries]
              ;; Two parent admissions legitimately transfer exactly one text
              ;; admission or four planner/collection/planner/render admissions.
              (dispatch (str "ai --max-tool-calls " (if (= kind :chart) 6 3) " --max-output-tokens 91 delegate"))
              (count @*queued*) => 1
              (with-redefs [runs/checkpoint!
                            (fn [session state handle]
                              (checkpoint session state handle)
                              (when (and (= :loop (:phase state))
                                         (zero? (- (:limit @(:budget session)) (:used @(:budget session))
                                                   (:reserved @(:budget session))))
                                         (or (= kind :pending) (get-in state [:loop :chart-terminal])
                                             (empty? (:tool_calls (ai/message provider (get-in state [:loop :body]))))))
                                (reset! committed {:row (read-row @(:row session)) :state state :budget @(:budget session)})
                                (throw (AssertionError. "interrupted after definitive final checkpoint commit"))))]
                ((first @*queued*)) => (throws AssertionError))
              (some? @committed) => true
              (:execution-phase (:row @committed)) => "ready"
              (:phase (runs/decode (:execution-state (:row @committed)) {})) => :loop
              (:execution-used (:row @committed)) => (:execution-limit (:row @committed))
              (:execution-limit (:row @committed)) => (if (= kind :chart) 4 1)
              (every? #(= "definitive" (:state %))
                      (json/parse-string (:operation-receipts (:row @committed)) true)) => true
              (origin/capability? @captured-context) => true
              @deliveries => []
              (let [before @wire receipts (:operation-receipts (:row @committed))
                    backend @@#'agent/runtime]
                (with-redefs [http/post (fn [& _] (throw (AssertionError. "recovery inferred")))
                              mcp/with-tools (fn [& _] (throw (AssertionError. "recovery discovered")))
                              mcp/call-tool (fn [& _] (throw (AssertionError. "recovery used MCP")))
                              kroki/client-call (fn [& _] (throw (AssertionError. "recovery rendered")))
                              datasets/retain! (fn [& _] (throw (AssertionError. "recovery collected")))
                              usage/capture! (fn [& _] (throw (AssertionError. "recovery captured usage")))
                              tools/finalize-checkpoint (fn [& args]
                                                          (let [r (apply finalize args)] (reset! finalized r) r))
                              runs/save-result! (fn [session result outcome]
                                                  (reset! saved {:result result :outcome outcome :budget @(:budget session)
                                                                 :row @(:row session) :owner (:owner session) :state @(:state session)})
                                                  (save-result session result outcome))]
                  (case recovery
                    :success
                    (do
                      ((:resume backend) (read-row (:row @committed)))
                      => {:status :unconfirmed :work-outcome (if (= kind :pending) :exhausted :completed)}
                      ((:resume backend) (read-row (:row @committed))) => {:status :terminal})
                    :revoked-user
                    (with-redefs [mcp/config (constantly {:servers [] :toolsets [(assoc policy :users ["REVOKED"])]})]
                      ((:resume backend) (read-row (:row @committed))) => (throws Exception))
                    :changed-provider
                    (with-redefs [ai/config (constantly {:providers [(assoc priced-provider :url "https://changed.invalid/v1")]})]
                      ((:resume backend) (read-row (:row @committed))) => (throws Exception))
                    :expired
                    (with-redefs [images/image-store (atom {})]
                      ((:resume backend) (read-row (:row @committed))) => (throws Exception))
                    :revoked-chart
                    (with-redefs [mcp/config (constantly {:servers [] :toolsets [(assoc policy :native-tools ["command"])]})]
                      ((:resume backend) (read-row (:row @committed))) => (throws Exception))))
                @wire => before
                (let [final (read-row (:row @committed))
                      text (get-in @saved [:result :result/value])]
                  (:execution-used final) => (:execution-limit final)
                  (:operation-receipts final) => receipts
                  (:execution-reserved final) => 0
                  @collections => (if (= kind :chart) 1 0)
                  @renders => (if (= kind :chart) 1 0)
                  (if (= recovery :success)
                    (do
                      (count @deliveries) => 1
                      (:budget @saved) => (:budget @committed)
                      (select-keys (:state @saved) [:family-id :authority :generation-cap])
                      => (select-keys (:state @committed) [:family-id :authority :generation-cap])
                      (get-in @saved [:row :worker-fence]) => (inc (get-in @committed [:row :worker-fence]))
                      (get-in @saved [:owner :fence]) => (get-in @saved [:row :worker-fence])
                      (get-in @saved [:row :state-revision]) => (inc (get-in @committed [:row :state-revision]))
                      (get-in @saved [:row :operation-receipts]) => receipts
                      (:outcome @saved) => (if (= kind :pending) :exhausted :completed)
                      (:execution-phase final) => "terminal"
                      (:execution-state final) => nil
                      (last (first @deliveries)) => text
                      (if (= kind :pending)
                        (do @finalized => nil
                            text => "Inherited allowance exhausted; no further inference or tool was dispatched.")
                        (do
                          (get-in @finalized [:result/data :ai/cost]) => (get-in @committed [:state :loop :accounting])
                          (pos? (get-in @finalized [:result/data :ai/cost :usd])) => true
                          (when (= kind :text) (get-in @finalized [:result/data :usage]))
                          => (when (= kind :text) {:prompt_tokens 7 :completion_tokens 3})
                          (if (= kind :text) text (get-in @saved [:result :result/public-data :artifacts]))
                          => (if (= kind :text) "The already completed final answer." [artifact])
                          (when (= kind :chart)
                            (string/includes? text (get-in @committed [:state :loop :chart-terminal :caption])))
                          => (when (= kind :chart) true))))
                    (do
                      @deliveries => []
                      @saved => nil
                      (:execution-phase final) => "ready"
                      (:execution-state final) => (:execution-state (:row @committed)))))))))))))
