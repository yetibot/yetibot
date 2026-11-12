(ns yetibot.test.integration.ai-agent-execution
  (:require [midje.sweet :refer [fact => =not=> throws]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clj-http.client :as http]
            [hara.io.scheduler :as scheduler]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.cloudflare :as cloudflare]
            [yetibot.api.clef :as clef]
            [yetibot.commands.ai :as ai-command]
            [yetibot.commands.openai :as openai]
            [yetibot.commands.weather :as weather]
            [yetibot.core.commands.agent :as core-agent]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.commands.collections]
            [yetibot.core.commands.echo]
            [yetibot.core.adapters.adapter :as adapters]
            [yetibot.core.models.users :as users]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.models.admin :as admin]
            [yetibot.core.db :as database]
            [yetibot.core.db.util :as db]
            [yetibot.core.db.agent-run :as ledger]
            [yetibot.core.db.cron :as cron]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.interpreter :as interpreter]
            [yetibot.core.chat :as chat]
            [yetibot.core.util.agent-execution :as origin]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.core.util.xai :as xai]
            [yetibot.core.util.gemini :as gemini]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-agent-runs :as runs]
            [yetibot.models.ai-cron-tools :as native-cron]
            [yetibot.util.ai-execution :as execution]
            [yetibot.util.ai-agent-execution :as agent]
            [yetibot.test.support.ai-execution :as support]))

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

(defn with-runtime [f]
  (let [cfg (db/config) prefix (str "app_u3_" (System/nanoTime) "_")
        callbacks agent/callbacks queued (atom [])]
    (when-not (re-find #":55445/" (:url cfg))
      (throw (ex-info "U3 requires owned PostgreSQL55445" {})))
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
                                    (callbacks (assoc options :schedule (fn [f] (swap! queued conj (bound-fn [] (f)))))))]
      (try
        (doseq [schema [ledger/schema cron/schema]]
          (database/idempotent-create-table! (:schema/table schema) (:schema/specs schema)))
        (agent/install!)
        (hooks/cmd-hook #"agent" #"run\s+(.+)" core-agent/agent-run-cmd
                        #"(?s)(.+)" core-agent/agent-cmd)
        (binding [*queued* queued] (f (:url cfg)))
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
            @effects => 3
            (:execution-used (ledger/read-row url "cron" (:id row))) => 3
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
              @effects => 7
              (:execution-used (ledger/read-row url "cron" (:id legacy))) => 4)))))))

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
            ((first @*queued*)) => {:status :terminal}
            @wire => 2
            (count @sent) => 1
            (:execution-state (ledger/read-row url "agent_run" (:id row))) => nil)))))))

(fact "restart readiness schedules a real saved queued row once only after current adapter is connected"
  (with-runtime
    (fn [_]
      (with-redefs [http/post (fn [_ _] {:status 200 :body (answer "agent report")})]
        (dispatch "ai --no-tools report | split , | xargs %s"))
      (reset! *queued* [])
      (with-redefs [adapters/adapters (atom {})]
        (support/in-event support/request "echo readiness" (fn [_] nil)))
      (count @*queued*) => 0
      (dotimes [_ 2]
        (support/in-event support/request "echo readiness" (fn [_] nil)))
      (count @*queued*) => 1)))

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
      (let [entered (promise) release (promise) state (atom nil) wire (atom 0) legacy (atom 0)]
        (with-redefs [handler/expr-eval-timeout-ms 300
                      core-agent/configured? (constantly false)
                      core-agent/say-unconfigured (fn [] (swap! legacy inc) "legacy")
                      http/post (fn [_ _]
                                  (reset! state execution/*request*)
                                  (swap! wire inc) (deliver entered true) @release
                                  {:status 200 :body (answer "agent late")})]
          (let [evaluation (future (dispatch "ai --no-tools report | split , | xargs %s"))]
            (deref entered 3000 false) => true
            (:timeout? (first (deref evaluation 3000 []))) => true
            (:closed? @(:budget @state)) => true
            (deliver release true)
            (Thread/sleep 100)
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
              (:execution-used (ledger/read-row url "cron" id)) => 3)))))))

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
