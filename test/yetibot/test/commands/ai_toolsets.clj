(ns yetibot.test.commands.ai-toolsets
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [taoensso.timbre :as log]
            [yetibot.commands.ai :as ai]
            [yetibot.api.ai :as api]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.chat :as core-chat]
            [yetibot.core.commands.cmd]
            [yetibot.core.commands.nil]
            [yetibot.core.commands.observe :as observe]
            [yetibot.core.db.observe :as observers]
            [yetibot.core.handler :as handler]
            [yetibot.core.interpreter :as interpreter]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.webapp.routes.api :as route]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.models.ai-github :as github]
            [yetibot.test.models.ai-github :as fixture]
            [yetibot.test.models.ai-tool-policy :refer [actor]]
            [yetibot.test.support.ai-execution :as chat]))

(def provider {:name "local" :url "http://localhost/v1" :model "chat"})

(defn stage []
  (proposals/stage! fixture/t actor provider "chat"
                    {"owner" "owner" "repo" "repo" "base" "main"
                     "title" "Fix" "body" "Change"
                     "files" [{"path" "src/a.clj" "content" "NEW_PRIVATE_MARKER\n"}]}
                    (fn [& _] {:base-sha fixture/sha
                               :originals {"src/a.clj" "ORIGINAL_PRIVATE_MARKER\n"}})))

(facts "toolset flags are leading options and cannot conflict with no-tools"
  (ai/parse-prompt "--provider local --toolset search hello")
  => {:provider "local" :toolset "search" :prompt "hello"}
  (doseq [input ["--toolset x --no-tools hello" "--no-tools --toolset x hello"
                 "--toolset x --toolset y hi"]]
    (fact (contains? (ai/prompt-cmd {:match input}) :result/error) => true)))

(fact "real interpreter passes trusted policy context separately from messages"
  (with-redefs [api/config (constantly {:providers [provider]})
                context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                tools/complete (fn [_ _ _ execution] execution)]
    (:value (chat/run-command chat/request "ai --toolset github-pr change it"))
    => {:toolset "github-pr" :actor actor}))

(fact "private toolsets require trusted adapter context, not copied metadata"
  (with-redefs [mcp/config (constantly fixture/config)]
    (ai/toolsets-cmd chat/request) => []
    (ai/servers-cmd chat/request) => []
    (:value (chat/run-command chat/request "ai toolsets")) => ["github-pr"]
    (:value (chat/run-command chat/request "ai servers"))
    => ["reader" "yetibot"]))

(fact "the real HTTP route cannot impersonate a chat user or access private tools"
  (let [connections (atom 0)
        source (assoc (:chat-source chat/request) :room "agent-room")]
    (with-redefs [mcp/config (constantly fixture/config)
                  api/config (constantly {:providers [provider]})
                  context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                  channel/settings-for-chat-source (constantly {})
                  mcp/with-tools (fn [& _] (swap! connections inc))]
      (route/api {:chat-source (pr-str source) :command "ai toolsets"} {}) => []
      (string/includes?
        (route/api {:chat-source (pr-str source)
                    :command "ai --toolset github-pr Read owner/repo"} {})
        "do not have access") => true
      @connections => 0)))

(fact "direct review and approval work once through the real interpreter"
  (let [p (stage) id (:id p) writes (atom 0)]
    (with-redefs [mcp/config (constantly fixture/config)
                  api/config (constantly {:providers [provider]})
                  tools/complete (fn [& _] (throw (Exception. "must not infer")))
                  github/execute! (fn [_ _] (swap! writes inc)
                                    {:status :succeeded :pr-url "https://github.com/owner/repo/pull/1"})]
      (:error (chat/run-command chat/request (str "ai approve " id))) => string?
      (:value (chat/run-command chat/request (str "ai proposal " id " 1"))) => string?
      (get-in (chat/run-command chat/request (str "ai approve " id)) [:data :status]) => :succeeded
      (:error (chat/run-command chat/request (str "ai approve " id))) => string?
      @writes => 1)))

(fact "model-generated commands cannot review or approve using inherited identity"
  (doseq [reviewed? [false true]]
    (let [p (stage) id (:id p) writes (atom 0)
          generated (if reviewed? (str "ai approve " id)
                      (str "ai proposal " id " 1 | nil | ai approve " id))]
      (when reviewed? (proposals/page! id actor 1))
      (with-redefs [mcp/config (constantly fixture/config)
                    api/config (constantly {:providers [provider]})
                    context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                    api/complete (constantly generated)
                    github/execute! (fn [& _] (swap! writes inc) {:status :succeeded})]
        (chat/run-command chat/request "ai --no-tools generate a command | cmd")
        (fact @writes => 0
              (:status (proposals/review! id actor)) => :pending
              (:reviewed-pages (proposals/review! id actor)) => (when reviewed? #{1}))))))

(fact "pipes and nested expressions are not direct review or approval"
  (let [p (stage) id (:id p)]
    (proposals/page! id actor 1)
    (with-redefs [mcp/config (constantly fixture/config)
                  api/config (constantly {:providers [provider]})
                  github/execute! (fn [& _] (throw (Exception. "must not write")))]
      (doseq [text [(str "ai approve " id " | nil")
                   (str "cmd ai approve " id)
                   (str "cmd `ai approve " id "`")
                   (str "ai proposal " id " 1 | nil")]]
        (chat/run-command chat/request text)
        (fact (:status (proposals/review! id actor)) => :pending)))))

(fact "an existing observer cannot approve a proposal with the triggering identity"
  (let [p (stage) id (:id p) writes (atom 0) completed (promise)
        record handler/record-and-run-raw]
    (proposals/page! id actor 1)
    (with-redefs [mcp/config (constantly fixture/config)
                  api/config (constantly {:providers [provider]})
                  channel/settings-for-chat-source (constantly {})
                  history/add (fn [& _])
                  github/execute! (fn [& _] (swap! writes inc) {:status :succeeded})
                  observers/find-all (constantly [{:event-type "message" :pattern "trigger"
                                                   :cmd (str "ai approve " id)}])
                  handler/record-and-run-raw
                  (fn [& args]
                    (let [result (doall (apply record args))]
                      (deliver completed result)
                      result))]
      (chat/in-event chat/request "trigger"
        (fn [request]
          (binding [interpreter/*chat-source* (:chat-source request)]
            (observe/obs-handler (assoc request :body "trigger" :event-type :message
                                       :yetibot-user {:id "BOT"})))))
      (fact (deref completed 5000 :timeout) => sequential?
            @writes => 0
            (:status (proposals/review! id actor)) => :pending))))

(fact "normal adapter delivery never logs review source, even with trace logging"
  (let [p (stage) id (:id p) logs (atom []) posted (promise)]
    (log/with-config (assoc log/*config* :level :trace :middleware []
                            :appenders {:capture {:enabled? true :async? false
                                                  :fn #(swap! logs conj (force (:output_ %)))}})
      (with-redefs [mcp/config (constantly fixture/config)
                    api/config (constantly {:providers [provider]})
                    channel/settings-for-chat-source (constantly {})
                    history/add (fn [& _]) observers/find-all (constantly [])
                    core-chat/send-paste #(deliver posted %)
                    github/execute! (fn [& _] {:status :succeeded})]
        (chat/in-event chat/request (str "ai proposal " id " 1")
          (fn [{:keys [chat-source user]}]
            (binding [core-chat/*target* "C1" core-chat/*adapter-uuid* "workspace"]
              (handler/handle-raw chat-source user :message {:id "BOT"}
                                  {:body (get-in chat-source [:raw-event :text])}))))
        (let [page (deref posted 5000 "TIMEOUT")]
          (fact (string/includes? page "ORIGINAL_PRIVATE_MARKER") => true
                (string/includes? page "NEW_PRIVATE_MARKER") => true))
        (chat/run-command chat/request (str "ai approve " id))
        (fact (some #(string/includes? % "ai-proposal") @logs) => true
              (some #(re-find #"ORIGINAL_PRIVATE_MARKER|NEW_PRIVATE_MARKER" %) @logs) => nil)
        (reset! logs [])
        (chat/run-command chat/request "ai providers")
        (fact (some #(string/includes? % "command-result") @logs) => true)))))
