(ns yetibot.test.integration.ai-followups
  (:require [clojure.core.async :as async]
            [clj-http.client :as client]
            [cheshire.core :as json]
            [yetibot.api.cloudflare :as cloudflare]
            [yetibot.test.integration.ai-autodraft :as autodraft]
            [clojure.string :as str]
            [clojure.java.jdbc :as jdbc]
            [discljord.connections :as websocket]
            [discljord.events :as events]
            [discljord.messaging :as messaging]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as api]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.opencode :as zen]
            [yetibot.commands.ai :as command]
            [yetibot.commands.grok]
            [yetibot.commands.zen]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.adapters.adapter :as adapters]
            [yetibot.core.db.util :as db]
            [yetibot.core.db.agent-run :as ledger]
            [yetibot.core.chat :as chat]
            [yetibot.core.config :as core-config]
            [yetibot.core.db.observe :as observers]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.models.users :as users]
            [yetibot.util.ai-execution :as execution]
            [yetibot.test.support.mcp :as scripted]))

(def settings
  {:default-provider "local"
   :providers [{:name "local" :url "http://localhost:11434/v1" :model "first"}
               {:name "zen" :url "https://opencode.ai/inference/openai/v1"
                :key "fixture-secret" :model "paid" :api "responses"}
               {:name "grok" :url "https://api.x.ai/v1" :model "grok-4.7"}]})
(defn delivered [value] (deliver (promise) value))
(defn event [id text & [parent]]
  (cond-> {:id id :channel-id "10" :content text
           :author {:id "7" :username "reader"}}
    parent (assoc :message-reference {:message-id parent :channel-id "10"})))
(defn await! [result]
  (when result
    (let [[_ port] (async/alts!! [result (async/timeout 5000)])]
      (when-not (= result port)
        (throw (ex-info "AI followup timed out" {}))))))
(defn session [f]
  (let [adapter (discord/make-discord {:name "test"}) conn (:conn adapter)
        messages (atom {}) next-id (atom 199) calls (atom [])
        config (atom settings) policy (atom {:servers []})
        reject? (atom false) gateway (async/chan 1)
        starts (atom 0) recovery-reads (atom 0) unexpected-db (atom 0)]
    (with-redefs [adapters/adapters (atom {(adapters/uuid adapter) adapter})
                  websocket/connect-bot! (fn [& _] gateway)
                  messaging/start-connection!
                  (fn [& _] (if (= 1 (swap! starts inc)) :fixture :status-fixture))
                  messaging/get-current-user! (fn [& _] (delivered {:id "9"}))
                  events/message-pump!
                  (fn [& _]
                    (fact (adapters/connected? adapter) => true
                          (get @adapters/adapters (adapters/uuid adapter)) => adapter))
                  jdbc/query
                  (fn [_ statement options]
                    (if (and (= statement
                                [(str "SELECT * FROM " (db/qualified-table-name "agent_run")
                                      " WHERE execution_kind=? AND execution_phase IN ('queued','ready','operation-in-flight')")
                                 ledger/inherited-kind])
                             (= #{:identifiers} (set (keys options))))
                      (do (swap! recovery-reads inc) [])
                      (do (swap! unexpected-db inc)
                          (throw (AssertionError. "Unexpected followup fixture query")))))
                  jdbc/get-connection
                  (fn [& _]
                    (swap! unexpected-db inc)
                    (throw (AssertionError. "Unexpected followup JDBC access")))
                  users/users (atom {})
                  history/add (constantly nil)
                  observers/find-all (constantly [])
                  channel/settings-for-chat-source (constantly {})
                  chat/chat-source
                  (fn [room] {:adapter :discord :uuid "test" :room room})
                  api/config #(deref config)
                  core-config/get-config (fn [_ path]
                                           (if (= [:ai] path)
                                             {:value @config}
                                             {:error :not-found}))
                  mcp/config #(deref policy)
                  api/models
                  (fn [_]
                    (with-meta ["paid" "free-a"]
                      {:catalog
                       {:models (mapv #(hash-map :id % :interface :chat
                                                :pricing {:kind (if (= % "free-a")
                                                                  "free" "paid")}
                                                :schema-summary {:api "responses"})
                                      ["paid" "free-a"])}}))
                  api/complete
                  (fn [provider model history & [options]]
                    (swap! calls conj {:provider provider :model model
                                       :messages history :options options})
                    (if @reject? (api/fail "fixture request rejected")
                      {:result/value "answer"}))
                  api/chat
                  (fn [provider model history & [options]]
                    (swap! calls conj {:provider provider :model model
                                       :messages history :options options})
                    (if @reject? (api/fail "fixture request rejected")
                      {:choices [{:message {:role "assistant"
                                            :content "answer"}}]}))
                  messaging/get-channel!
                  (fn [& _] (delivered {:id "10" :type 0}))
                  messaging/get-channel-message!
                  (fn [_ _ id] (delivered (get @messages id)))
                  messaging/create-message!
                  (fn [connection room & options]
                    (if (= connection :status-fixture)
                      (delivered {:id "status" :channel-id room})
                      (let [options (apply hash-map options)
                          reference (:message-reference options)
                          latest (apply max (map #(Long/parseLong %)
                                                 (keys @messages)))
                          id (str (swap! next-id #(inc (max % latest))))
                          result (cond-> {:id id :channel-id room
                                          :author {:id "9" :bot true}
                                          :components (:components options)}
                                   reference
                                   (assoc :message-reference
                                          {:message-id (:message_id reference)
                                           :channel-id
                                           (:channel_id reference)}))]
                      (swap! messages assoc id result)
                      (delivered result))))
                  messaging/edit-message!
                  (fn [connection room id & options]
                    (if (= connection :status-fixture)
                      (delivered {:id id :channel-id room})
                      (do (swap! messages update id merge
                                 (select-keys (apply hash-map options) [:components]))
                          (delivered (get @messages id)))))
                  messaging/delete-message!
                  (fn [_ _ id] (swap! messages dissoc id) (delivered true))
                  messaging/trigger-typing-indicator! (fn [& _] (delivered true))]
      (try
        (adapters/start adapter)
        (fact @starts => 2
              (adapters/connected? adapter) => true
              @(:yetibot-user adapter) => {:id "9"})
        (binding [chat/*adapter* adapter]
          (f {:adapter adapter :conn conn :messages messages :calls calls
              :config config :policy policy :reject? reject?
              :send! (fn [message]
                       (swap! messages assoc (:id message) message)
                       (await!
                         (discord/handle-event :message-create message conn
                                               (:yetibot-user adapter))))}))
        (fact (pos? @recovery-reads) => true
              @unexpected-db => 0)
        (finally (async/close! gateway))))))

(fact "plain replies inherit AI, Grok or Zen without reinterpreting their text"
  (doseq [[command provider model options]
          [["ai" "local" "first" "--no-tools "]
           ["grok" "grok" "grok-4.7" "--no-tools "]
           ["zen" "zen" "paid" "--model paid "]]]
    (session
      (fn [{:keys [send! calls config]}]
        (send! (event "100" (str "!" command " " options "first prompt")))
        (swap! config assoc :default-provider "zen")
        (swap! config assoc-in [:providers 0 :model] "changed-default")
        (send! (event "300" "--model attacker | echo `echo pwn`" "200"))
        (count @calls) => 2
        (mapv #(get-in % [:provider :name]) @calls) => [provider provider]
        (mapv :model @calls) => [model model]
        (let [history (:messages (second @calls))]
          (:content (last history)) => "--model attacker | echo `echo pwn`"
          (count (filter #(= "--model attacker | echo `echo pwn`"
                              (:content %)) history)) => 1
          (boolean (some #(= "assistant" (:role %)) history)) => true)
        (send! (event "400" "third turn" "301"))
        (count @calls) => 3))))

(fact "explicit commands switch branches; unrelated chatter stays ignored"
  (session
    (fn [{:keys [send! calls]}]
      (send! (event "100" "!zen --model paid first"))
      (send! (event "300" "!ai --provider local --no-tools switch" "200"))
      (send! (event "400" "continue the local branch" "301"))
      (mapv #(get-in % [:provider :name]) @calls) => ["zen" "local" "local"]
      (send! (event "500" "unrelated chatter"))
      (send! (assoc-in (event "501" "bot loop" "301") [:author :bot] true))
      (count @calls) => 3)))

(fact "failed model answers cannot implicitly retry"
  (session
    (fn [{:keys [send! calls reject?]}]
      (reset! reject? true)
      (send! (event "100" "!zen --model paid first"))
      (reset! reject? false)
      (send! (event "300" "do not retry" "200"))
      (count @calls) => 1)))

(fact "provider changes and unreadable context fail before another inference"
  (doseq [change [:provider :ancestor]]
    (session
      (fn [{:keys [send! calls config messages]}]
        (send! (event "100" "!ai --no-tools first"))
        (case change
          :provider (swap! config assoc-in [:providers 0 :url]
                           "https://different.test/v1")
          :ancestor (swap! messages dissoc "100"))
        (send! (event "300" "followup" "200"))
        (count @calls) => 1))))

(fact "each continuation uses current actor and current toolset permissions"
  (session
    (fn [{:keys [send! calls policy]}]
      (reset! policy
              {:servers [] :toolsets
               [{:name "scoped" :kind "search" :servers [] :native-tools []
                 :users ["7"] :providers [{:name "zen" :models ["paid"]}]}]})
      (send! (event "100" "!zen --model paid --toolset scoped first"))
      (swap! policy assoc-in [:toolsets 0 :users] ["someone-else"])
      (send! (event "300" "permissions revoked" "200"))
      (count @calls) => 1)))

(fact "pipeline and embedded AI output never replay their originating commands"
  (doseq [body ["!ai --no-tools first | echo %s"
                "`ai --no-tools first`"]]
    (session
      (fn [{:keys [send! calls conn]}]
        (send! (event "100" body))
        (send! (event "300" "plain followup" "200"))
        (count @calls) => 1
        (some-> (get-in @conn [:commands ["10" "100"]])
                :continuation deref) => nil))))

(fact "explicit toolset and budget are inherited rather than prompt flags"
  (session
    (fn [{:keys [send! calls conn policy]}]
      (reset! policy
        {:servers [] :default-toolset "scoped"
         :toolsets [{:name "scoped" :kind "search" :servers [] :native-tools []
                     :providers [{:name "local" :models ["first"]}]}
                    {:name "other" :kind "search" :servers [] :native-tools []
                     :providers [{:name "zen" :models ["paid"]}]}]})
      (send! (event "100"
               "!ai --toolset scoped --max-tool-calls 2 first"))
      (let [route (:route @(get-in @conn [:commands ["10" "100"]
                                        :continuation]))]
        (select-keys route [:toolset :max-tool-calls])
        => {:toolset "scoped" :max-tool-calls 2})
      (swap! policy assoc :default-toolset "other")
      (send! (event "300" "--toolset other --max-tool-calls 20" "200"))
      (mapv :model @calls) => ["first" "first"])))

(fact "a different replier never inherits the original actor's permission"
  (session
    (fn [{:keys [send! calls policy]}]
      (reset! policy
        {:servers [] :toolsets
         [{:name "scoped" :kind "search" :servers [] :native-tools []
           :users ["7"] :providers [{:name "zen" :models ["paid"]}]}]})
      (send! (event "100" "!zen --model paid --toolset scoped first"))
      (send! (assoc-in (event "300" "try someone else's tools" "200")
                       [:author :id] "8"))
      (count @calls) => 1)))

(fact "ownership lost while reading ancestors stops inference, not into parsing"
  (session
    (fn [{:keys [send! calls conn]}]
      (send! (event "100" "!ai --no-tools first"))
      (let [read-message messaging/get-channel-message!]
        (with-redefs [messaging/get-channel-message!
                      (fn [connection room id]
                        (when (= id "100")
                          (reset! (get-in @conn [:commands ["10" "100"]
                                                :revision]) (Object.)))
                        (read-message connection room id))]
          (send! (event "300" "literal `echo pwn` | ai retry" "200"))))
      (count @calls) => 1)))

(fact "an automatic free conversation cannot silently become paid or rotate"
  (doseq [command ["!ai --provider zen --no-tools" "!zen"]]
    (session
      (fn [{:keys [send! calls config conn]}]
        (swap! config assoc-in [:providers 1 :model] nil)
        (let [free (atom ["free-a"])]
          (with-redefs [zen/free-models (fn [_] @free)]
            (send! (event "100" (str command " first")))
            (:verified-free (:route @(get-in @conn
                                       [:commands ["10" "100"] :continuation])))
            => true
            (reset! free ["different-free-model"])
            (send! (event "300" "do not incur new charges" "200"))
            (mapv :model @calls) => ["free-a"]))))))

(fact "unexpected follow-up failures disclose uncertainty without raw errors"
  (session
    (fn [{:keys [send! calls messages]}]
      (send! (event "100" "!ai --no-tools first"))
      (with-redefs [execution/run-followup
                    (fn [& _] (throw (Exception. "PRIVATE-ERROR")))]
        (send! (event "300" "followup" "200")))
      (count @calls) => 1
      (str/includes? (pr-str (get @messages "301")) "PRIVATE") => false
      (str/includes? (pr-str (get @messages "301"))
                     "do not retry automatically") => true)))


(defn implicit-zen-followup
  ([mutation] (implicit-zen-followup mutation {}))
  ([mutation {:keys [root-mode api] :or {api :responses}}]
  (let [chat-wire api/chat complete-wire api/complete models-wire api/models
        read-bytes cloudflare/read-bytes preparations (atom []) sent (atom [])
        post-wire client/post physical-requests (atom [])
        remember execution/remember-answer! observations (atom []) selected-values (atom []) costs (atom [])
        remember-route discord/remember-command-continuation! routes (atom [])
        prepare-followup command/prepare-followup reply-errors (atom [])
        dialect (atom api) catalog-model (atom "free-a")
        file (java.io.File/createTempFile "zen-continuation-" ".key")
        success? (contains? #{nil :default-model} mutation)
        root-id (System/nanoTime) root (str root-id) root-reply (str (inc root-id))]
    (spit file "zen-followup-fixture")
    (try
      (scripted/with-http-server
        (fn [{:keys [path] :as request}]
          (swap! physical-requests conj request)
          {:body (if (str/includes? path "/ai/run/")
                   (:body (autodraft/classification :answer))
                   (if (= api :responses)
                     {:id "followup-response" :status "completed"
                      :usage {:input_tokens 7 :output_tokens 3}
                      :output [{:type "message" :role "assistant"
                                :content [{:type "output_text" :text "bounded root answer"}]}]}
                     {:choices [{:message {:role "assistant" :content "bounded root answer"}}]
                      :usage {:prompt_tokens 7 :completion_tokens 3}}))})
        (fn [base]
          (session
        (fn [{:keys [send! config policy messages conn]}]
          (swap! config assoc :default-provider "zen"
                 :providers [{:name "zen" :url "https://opencode.ai/inference/openai/v1"
                              :key-file (.getAbsolutePath file)
                              :model (when (= root-mode :configured) "free-a")}]
                 :intent-router {:enabled true :github-toolset "github-pr"
                                 :account-id (apply str (repeat 32 "a")) :key-file "/fixture/router-key"})
          (let [t {:name "answer" :kind "search" :servers [] :native-tools []
                   :users ["7"] :adapters ["discord"]
                   :providers [{:name "zen" :models ["free-a"]}
                               {:name "cloudflare" :models ["@cf/cloudflare/clef"]}]}]
            (reset! policy {:default-toolset "answer"
                            :servers [{:name "reader" :tools ["get_commit" "get_file_contents"]
                                       :github-api-token "followup-ancestry-fixture"}
                                      {:name "writer" :tools ["create_branch" "push_files" "create_pull_request"]}]
                            :toolsets [t (assoc (dissoc t :native-tools) :name "github-pr" :kind "github-pr"
                                                :repositories ["owner/repo"] :base-branches ["main"]
                                                :allowed-paths ["src/"] :write-server "writer"
                                                :servers [{:name "reader" :tools ["get_commit" "get_file_contents"]}])]}))
          (with-redefs [api/chat chat-wire api/complete complete-wire api/models models-wire
                        cloudflare/read-bytes
                        (fn [& args]
                          (swap! preparations conj execution/*request*)
                          (apply read-bytes args))
                        execution/remember-answer!
                        (fn [command prepared result]
                          (swap! selected-values conj prepared)
                          (swap! costs conj (get-in result [:result/data :ai/cost]))
                          (swap! observations conj {:capture execution/*command-capture*
                                                    :direct execution/*direct-input*
                                                    :caption (:caption? prepared) :actor (:actor prepared)
                                                    :value (:result/value result) :error (:result/error result)})
                          (remember command prepared result))
                        discord/remember-command-continuation!
                        (fn [route] (swap! routes conj route) (remember-route route))
                        command/prepare-followup
                        (fn [& args]
                          (try (apply prepare-followup args)
                               (catch Exception e
                                 (swap! reply-errors conj (.getMessage e))
                                 (throw e))))
                        yetibot.api.clef/credential! (constantly "router-fixture")
                        client/get
                        (fn [_ options]
                          (swap! preparations conj execution/*request*)
                          (let [body {:data [{:id @catalog-model :type "chat"
                                             :modalities {:input ["text"] :output ["text"]}
                                             :api {@dialect {}}
                                             :cost {:text {:input {:price (if root-mode 1 0)} :output {:price (if root-mode 2 0)}}}}]}]
                            {:status 200 :body (if (= :stream (:as options))
                                                (java.io.ByteArrayInputStream.
                                                  (.getBytes (json/generate-string body) "UTF-8")) body)}))
                        client/post
                        (fn [url options]
                          (swap! sent conj {:url url :body (or (:form-params options)
                                                            (when (string? (:body options))
                                                              (json/parse-string (:body options) true)))
                                            :request execution/*request* :direct execution/*direct-input*
                                            :admissions (execution/admissions execution/*request*)})
                          ;; Keep the provider's authenticated origin/signature,
                          ;; but route its transport to an owned real TCP fixture.
                          (post-wire (str base (.getRawPath (java.net.URI. url))) options))]
            (let [text (str "!ai " (when (= root-mode :explicit) "--provider zen --model free-a ")
                            "--max-tool-calls 2 --max-output-tokens 91 Explain owner/repo")]
              (send! (event root text)))
            (fact (count @sent) => 2
                  (count @routes) => 1
                  (set (keys @messages)) => #{root root-reply}
                  (mapv #(some-> % :continuation deref :ready?) (vals (:commands @conn))) => [true]
                  (set (keys (first @routes)))
                  => (cond-> #{:command :provider :model :provider-signature :max-tool-calls :max-output-tokens}
                       (nil? root-mode) (conj :verified-free))
                  @observations => [{:capture false
                                     :direct (let [text (str "ai " (when (= root-mode :explicit) "--provider zen --model free-a ")
                                                               "--max-tool-calls 2 --max-output-tokens 91 Explain owner/repo")]
                                               {:raw text :evaluated text})
                                     :caption true
                                     :actor {:adapter "discord" :connection "test" :user "7"
                                             :conversation "test/10" :thread "10"}
                                     :value "bounded root answer" :error nil}])
            (let [before (count @preparations) route (first @routes)
                  forged (with-meta (assoc route :max-output-tokens 262144)
                           {:yetibot.api.ai/configured-signature (:provider-signature route)})]
              (fact (try (prepare-followup {:match "forged"} forged api/config) false
                         (catch Exception _ true)) => true
                    (count @preparations) => before
                    (count @sent) => 2))
            (case mutation
              :default-model (swap! config assoc-in [:providers 0 :model] "different-default")
              :api (swap! config assoc-in [:providers 0 :api] "responses")
              :endpoint (swap! config assoc-in [:providers 0 :url] "https://changed.invalid/v1")
              :native (swap! config assoc-in [:providers 0 :native-tools] ["web_search"])
              :turns (swap! config assoc-in [:providers 0 :max-turns] 4)
              :gateway (swap! config assoc-in [:providers 0 :gateway-id] "changed")
              :transport (swap! config assoc-in [:providers 0 :continuation-transport] "changed")
              :access (swap! policy assoc-in [:toolsets 0 :users] ["other-user"])
              :dialect (reset! dialect :chat-completions)
              :model (reset! catalog-model "different-model")
              nil)
            (let [before (count @preparations)]
              (send! (event (str (+ root-id 100)) "--model attacker | echo not-a-command" root-reply))
              (if success?
                (do
                  (fact @reply-errors => []
                        (count @sent) => 3
                        (mapv #(get-in % [:body :model]) (remove #(str/includes? (:url %) "/ai/run/") @sent))
                        => ["free-a" "free-a"]
                        (str/includes? (pr-str (or (:input (:body (last @sent))) (:messages (:body (last @sent)))))
                                       "--model attacker | echo not-a-command") => true)
                  (send! (event (str (+ root-id 200)) "one more literal turn" (str (+ root-id 101))))
                  (fact (count @sent) => 4))
                (do
                  (fact (count @sent) => 2)
                  (when-not (#{:dialect :model} mutation)
                    (fact (count @preparations) => before)))))
            (when root-mode
              (fact (get-in (first @selected-values) [:provider :api]) => (name api)
                    (get-in (first @selected-values) [:provider :ai/verified-free]) => nil
                    (:usd (first @costs)) => 0.000013M
                    (:scope (first @costs)) => :tokens
                    (:turns (first @costs)) => 1))
            (fact (mapv #(get-in % [:admissions :admitted]) (take 2 @sent)) => [1 2]
                  (every? some? @preparations) => true
                  (every? #(= 91 (or (get-in % [:body :max_output_tokens]) (get-in % [:body :max_tokens])))
                          (remove #(str/includes? (:url %) "/ai/run/") @sent)) => true
                  (every? #(nil? (:direct %)) @sent) => true
                  (count (distinct (map #(get-in % [:request :family-id]) (take 2 @sent)))) => 1
                  (every? #(= 2 (:limit @(:budget (:request %)))) @sent) => true))))
          (fact (count @physical-requests) => (count @sent)
                (mapv :body @physical-requests) => (mapv :body @sent))))
      (finally (.delete file))))))

(fact "implicit Zen routed root answer delivers real continuations with frozen model dialect and caps"
  (doseq [mutation [nil :default-model]] (implicit-zen-followup mutation)))

(fact "implicit Zen continuation rejects configured identity access model and catalog dialect drift before inference"
  (doseq [mutation [:api :endpoint :native :turns :gateway :transport :access :dialect :model]]
    (implicit-zen-followup mutation)))

(fact "explicit and configured paid Zen roots retain their selected omitted-API dialect for genuine human replies"
  (doseq [root-mode [:explicit :configured] api [:responses :chat-completions]]
    (implicit-zen-followup nil {:root-mode root-mode :api api})))

(fact "explicit and configured Zen roots reject identity policy model and selected catalog drift before continuation inference"
  (doseq [root-mode [:explicit :configured]
          mutation [:api :endpoint :native :turns :gateway :transport :access :dialect :model]]
    (implicit-zen-followup mutation {:root-mode root-mode})))
