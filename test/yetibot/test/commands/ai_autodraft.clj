(ns yetibot.test.commands.ai-autodraft
  (:require [midje.sweet :refer [facts fact =>]]
            [yetibot.api.ai :as api] [yetibot.api.clef :as clef]
            [yetibot.api.mcp :as mcp] [yetibot.commands.ai]
            [yetibot.models.ai-context :as context] [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-proposals :as proposals] [yetibot.models.ai-github :as github]
            [yetibot.test.models.ai-github :as fixture]
            [yetibot.test.support.ai-execution :as chat]
            [yetibot.core.commands.cmd] [yetibot.core.commands.nil]
            [yetibot.core.webapp.routes.api :as route]
            [yetibot.core.models.channel :as channel]
            [yetibot.models.ai-autodraft :as autodraft]))

(def provider {:name "local" :url "http://localhost/v1" :model "chat"})
(def router {:enabled true :github-toolset "github-pr" :account-id (apply str (repeat 32 "a"))
             :key-file "/unused/fixture-clef-token"})
(defn request [] (assoc-in chat/request [:chat-source :raw-event :ts] (str (java.util.UUID/randomUUID))))
(defn scenario [f]
  (let [classified (atom []) writes (atom []) generations (atom [])
        generation-options (atom [])]
    (with-redefs [channel/settings-for-chat-source (constantly {})
                  api/config (constantly {:providers [provider] :intent-router router})
                  mcp/config (constantly fixture/config)
                  context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                  clef/credential! (constantly "fixture-clef-secret")
                  clef/classify! (fn [_ prompt] (swap! classified conj prompt) :code-change)
                  tools/complete
                  (fn [p model _ {:keys [actor toolset] :as options}]
                    (swap! generations conj toolset)
                    (swap! generation-options conj options)
                    (if (= toolset "github-pr")
                      (let [p (proposals/stage! fixture/t actor p model
                                {"owner" "owner" "repo" "repo" "base" "main" "title" "Fix" "body" "Fix"
                                 "files" [{"path" "src/a.clj" "content" "new\n"}]}
                                (fn [& _] {:base-sha fixture/sha :originals {"src/a.clj" "old\n"}}))]
                        {:result/data {:proposal (:id p)}})
                      "ordinary answer"))
                  github/execute! (fn [_ p] (swap! writes conj p)
                                    {:status :succeeded :pr-url "https://github.com/owner/repo/pull/1"})]
      (f {:classified classified :writes writes :generations generations
          :generation-options generation-options}))))

(fact "automatic code-change routing forwards only explicit tool-call budgets"
  (doseq [[flag budget] [["--max-tool-calls 12 " 12]
                        ["--max-tool-calls 1 " 1]
                        ["" nil]]]
    (scenario
      (fn [{:keys [writes generations generation-options]}]
        (let [result (chat/run-command
                       (request) (str "ai " flag "Fix owner/repo"))
              options (first @generation-options)]
          (fact (get-in result [:data :status]) => :succeeded
                @generations => ["github-pr"]
                (count @writes) => 1
                (count @generation-options) => 1
                (:max-tool-calls options) => budget
                (contains? options :max-tool-calls) => (some? budget)))))))

(fact "one direct authorized change request creates a draft without page approval, once"
  (scenario (fn [{:keys [writes classified]}]
    (let [r (request) result (chat/run-command r "ai Fix owner/repo")]
      (fact (get-in result [:data :status]) => :succeeded
            (get-in result [:data :pr-url]) => "https://github.com/owner/repo/pull/1"
            (count @writes) => 1
            (:execution (first @writes)) => :automatic
            (:status (first @writes)) => :executing
            (:reviewed-pages (first @writes)) => nil
            (:request-key (first @writes)) => #"[a-f0-9]{64}"
            @classified => ["Fix owner/repo"])
      (chat/run-command r "ai Fix owner/repo")
      (fact (count @writes) => 1)))))

(facts "question and ambiguity decisions never stage or write"
  (doseq [decision [:answer :clarify]]
    (scenario (fn [{:keys [writes generations]}]
      (with-redefs [clef/classify! (constantly decision)]
        (chat/run-command (request) "ai Explain owner/repo")
        (fact @writes => []
              (some #{"github-pr"} @generations) => nil))))))

(facts "untrusted, generated, piped, and explicit manual requests cannot auto-write"
  (doseq [text ["ai Fix owner/repo | nil" "cmd ai Fix owner/repo"
                "ai --toolset github-pr Fix owner/repo" "cmd `ai Fix owner/repo`"]]
    (scenario (fn [{:keys [writes classified]}]
      (chat/run-command (request) text)
      (fact @writes => [] @classified => []))))
  (scenario (fn [{:keys [writes classified]}]
    (with-redefs [clef/classify! (fn [& _] (throw (Exception. "must not classify")))]
      (chat/run-command (assoc-in (request) [:user :id] "UNAUTHORIZED") "ai Fix owner/repo")
      (fact @writes => [] @classified => [])))))

(fact "read-only questions do not consume the bounded write-receipt capacity"
  (scenario (fn [{:keys [writes]}]
    (with-redefs [clef/classify! (constantly :answer)]
      (dotimes [_ 130] (chat/run-command (request) "ai Explain owner/repo")))
    (fact (get-in (chat/run-command (request) "ai Fix owner/repo") [:data :status]) => :succeeded
          (count @writes) => 1))))

(fact "quoted literal text stays literal and the original question reaches Clef"
  (scenario (fn [{:keys [writes classified]}]
    (chat/run-command (request) "ai   Fix owner/repo and use \"literal text\"")
    (fact (count @writes) => 1
          @classified => ["Fix owner/repo and use \"literal text\""]))))

(fact "repository selection is explicit and a multi-repository ask is clarified"
  (scenario (fn [{:keys [writes generations]}]
    (doseq [text ["ai Fix the parser" "ai Fix outsider/project"]]
      (let [result (chat/run-command (request) text)]
        (fact (:value result) => string? @writes => [] @generations => [])))
    (with-redefs [mcp/config (constantly
                              (assoc fixture/config :toolsets
                                     [(assoc fixture/t :repositories ["owner/repo" "owner/other"])]))]
      (chat/run-command (request) "ai Fix owner/repo and owner/other")
      (fact @writes => [] @generations => [])))))

(fact "copied HTTP identity and generated model commands cannot trigger classification"
  (scenario (fn [{:keys [writes classified]}]
    (route/api {:chat-source (pr-str (assoc (:chat-source (request)) :room "agent-room"))
                :command "ai Fix owner/repo"} {})
    (with-redefs [api/complete (constantly "ai Fix owner/repo")]
      (chat/run-command (request) "ai --no-tools generate a command | cmd"))
    (fact @writes => [] @classified => []))))

(fact "replay cannot turn an earlier read-only decision into a write"
  (scenario (fn [{:keys [writes]}]
    (let [r (request)]
      (with-redefs [clef/classify! (constantly :answer)]
        (chat/run-command r "ai Explain owner/repo"))
      (chat/run-command r "ai Explain owner/repo")
      (fact @writes => [])))))

(fact "concurrent delivery of one platform event executes once"
  (scenario (fn [{:keys [writes]}]
    (let [r (request)
          jobs (doall (repeatedly 4 #(future (chat/run-command r "ai Fix owner/repo"))))]
      (doseq [job jobs] (deref job 5000 :timeout))
      (fact (count @writes) => 1)))))

(fact "failed execution is recorded and never automatically retried on replay"
  (scenario (fn [{:keys [writes]}]
    (let [r (request)]
      (with-redefs [github/execute! (fn [_ p]
                                      (swap! writes conj p)
                                      {:status :unknown :branch "known-branch"})]
        (chat/run-command r "ai Fix owner/repo")
        (chat/run-command r "ai Fix owner/repo")
        (fact (count @writes) => 1))))))

(fact "a model cannot redirect the change into another allowed repository"
  (scenario (fn [{:keys [writes]}]
    (let [t (assoc fixture/t :repositories ["owner/repo" "owner/other"])]
      (with-redefs [mcp/config (constantly (assoc fixture/config :toolsets [t]))
                    tools/complete
                    (fn [p model _ {:keys [actor]}]
                      (let [p (proposals/stage! t actor p model
                                {"owner" "owner" "repo" "other" "base" "main" "title" "Fix" "body" "Fix"
                                 "files" [{"path" "src/a.clj" "content" "new\n"}]}
                                (fn [& _] {:base-sha fixture/sha :originals {"src/a.clj" "old\n"}}))]
                        {:result/data {:proposal (:id p)}}))]
        (fact (:error (chat/run-command (request) "ai Fix owner/repo")) => string?
              @writes => []))))))

(fact "a classifier outage fails closed without generation or writes"
  (scenario (fn [{:keys [writes generations]}]
    (with-redefs [clef/classify! (fn [& _] (api/fail "Clef unavailable"))]
      (let [result (chat/run-command (request) "ai Fix owner/repo")]
        (fact (:error result) => string? @writes => [] @generations => []))))))
