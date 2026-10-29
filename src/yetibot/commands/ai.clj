(ns yetibot.commands.ai
  (:require [clojure.string :as string]
            [yetibot.api.ai :as ai]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-autodraft :as autodraft]
            [yetibot.models.ai-tools :as tools]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.models.ai-github :as github]
            [yetibot.util.ai-execution :as execution]))

(def usage "Usage: ai [--provider <name>] [--model <model>] [--toolset <name> | --no-tools] <prompt>")

(defn parse-prompt
  "Only leading flags are options. Use -- to start a prompt with a flag."
  ([args] (parse-prompt args usage))
  ([args usage] (parse-prompt args usage {}))
  ([args usage switches]
   (loop [remaining (string/trim args) options {}]
     (cond
       (string/blank? remaining) (ai/fail usage)
       (string/starts-with? remaining "--")
       (let [[flag rest-args] (string/split remaining #"\s+" 2)
             switch (get (assoc switches "--no-tools" :no-tools) flag)]
         (cond
           (= "--" flag)
           (if (string/blank? rest-args)
             (ai/fail usage)
             (do
               (when (and (:toolset options) (:no-tools options))
                 (ai/fail usage))
               (assoc options :prompt rest-args)))
           switch
           (if (contains? options switch)
             (ai/fail usage)
             (recur (or rest-args "") (assoc options switch true)))
           :else
           (let [option ({"--provider" :provider "--model" :model
                          "--toolset" :toolset} flag)
                 [value rest-args] (when rest-args
                                     (string/split rest-args #"\s+" 2))]
             (when-not (and option (ai/nonblank-string? value)
                            (not (string/starts-with? value "--"))
                            (not (contains? options option)))
               (ai/fail usage))
             (recur (or rest-args "") (assoc options option value)))))
       :else (do
               (when (and (:toolset options) (:no-tools options))
                 (ai/fail usage))
               (assoc options :prompt remaining))))))

(defn safely [f]
  (try
    (f)
    (catch Exception e
      {:result/error (if (:ai/error (ex-data e))
                       (.getMessage e)
                       "The AI request failed. Check the provider configuration and try again.")})))

(defn providers-cmd
  "ai providers # list configured AI provider names"
  {:yb/cat #{:ai}}
  [_]
  (safely #(mapv :name (:providers (ai/config)))))

(defn models-cmd
  "ai models # list available models grouped by provider
   ai models <provider> # list available models for one provider"
  {:yb/cat #{:ai}}
  [{[_ provider-name] :match}]
  (safely
    (fn []
      (let [config (ai/config)
            providers (if provider-name [(ai/provider config provider-name)] (:providers config))
            results (mapv (fn [provider]
                            (safely #(let [models (ai/models provider)]
                                       (cond-> {:models models}
                                         (:catalog (meta models))
                                         (assoc :catalog
                                                (:catalog (meta models)))))))
                          providers)]
        {:result/data (zipmap (map :name providers) results)
         :result/value (mapv (fn [provider result]
                               (str (:name provider) ": "
                                    (or (:result/error result)
                                        (when-let [catalog (:catalog result)]
                                          (str (:coverage catalog) "\n"
                                               (string/join
                                                 "\n"
                                                 (map #(str (:id %) " ["
                                                            (name
                                                              (:interface %))
                                                            "]")
                                                      (:models catalog)))))
                                        (when (seq (:models result))
                                          (string/join ", " (:models result)))
                                        "No models available.")))
                             providers results)}))))

(defn servers-cmd
  "ai servers # list configured MCP and native tool sources"
  {:yb/cat #{:ai}}
  [args]
  (safely #(tools/server-names (policy/actor args))))

(defn tools-cmd
  "ai tools # list allowed MCP and native tools
   ai tools <server> # list allowed tools from one source"
  {:yb/cat #{:ai}}
  [{[_ server-name] :match :as args}]
  (safely
    #(let [catalog (tools/tool-catalog server-name (policy/actor args))]
       {:result/value (:names catalog)
        :result/data (dissoc catalog :names)})))

(defn toolsets-cmd
  "ai toolsets # list toolsets available to you in this conversation"
  {:yb/cat #{:ai}}
  [args]
  (safely #(tools/toolset-names (policy/actor args))))

(defn proposal-cmd
  "ai proposal <id> [page] # inspect a proposal and review every diff page"
  {:yb/cat #{:ai}}
  [{[_ id page] :match :as args}]
  (safely #(do
             (execution/require-direct! args)
             (execution/sensitive!)
             (if page
               (proposals/page! id (policy/actor args) (Long/parseLong page))
               (proposals/preview (proposals/review! id (policy/actor args)))))))

(defn cancel-cmd
  "ai cancel <id> # cancel your pending change proposal"
  {:yb/cat #{:ai}}
  [{[_ id] :match :as args}]
  (safely #(do (execution/require-direct! args)
               (name (proposals/cancel! id (policy/actor args))))))

(defn approve-cmd
  "ai approve <id> # execute exactly the reviewed change as a draft PR"
  {:yb/cat #{:ai}}
  [{[_ id] :match :as args}]
  (safely
    (fn []
      (execution/require-direct! args)
      (execution/sensitive!)
      (let [actor (policy/actor args)
            p (proposals/review! id actor)
            config (mcp/config)
            t (:toolset (toolsets/select config (get-in p [:toolset :name])))
            provider (ai/provider (ai/config) (get-in p [:provider :name]))]
        (policy/authorize! t actor provider (:model p))
        (when-not (and (= t (:toolset p))
                       (= (select-keys provider [:name :url]) (:provider p))
                       (ai/tool-calling? provider))
          (ai/fail "Provider or policy changed. Generate a new proposal."))
        (let [claimed (proposals/claim! id actor)
              result (github/execute! config claimed)]
          (proposals/finish! id result)
          {:result/data result
           :result/value (str "GitHub workflow: " (name (:status result))
                              (when-let [url (:pr-url result)] (str "\n" url))
                              (when-let [step (:step result)] (str "\nStep: " (name step)))
                              (when-let [branch (:branch result)] (str "\nBranch: " branch))
                              (when-let [commit (:commit result)] (str "\nCommit: " commit))
                              (when-let [message (:message result)] (str "\n" message)))})))))

(defn prepare-prompt
  "Pin provider/model and trusted policy context before any asynchronous work."
  ([args] (prepare-prompt args nil usage))
  ([args command-provider prompt-usage]
   (prepare-prompt args command-provider prompt-usage ai/config))
  ([args command-provider prompt-usage resolve-config]
   (prepare-prompt args command-provider prompt-usage resolve-config {}))
  ([{:keys [match] :as args} command-provider prompt-usage resolve-config
    switches]
   (let [options (parse-prompt match prompt-usage switches)
         _ (when (and command-provider (:provider options))
             (ai/fail prompt-usage))
         config (resolve-config)
         provider-name (or command-provider (:provider options))
         _ (when (and command-provider
                      (not-any? #(= provider-name (:name %))
                                (:providers config)))
             (ai/fail (str "AI provider " provider-name
                           " is not configured. Add it to ai.providers.")))
         provider (ai/provider config provider-name)
         model (or (:model options) (:model provider))]
     (when-not model
       (ai/fail (str "No default model configured for this provider. "
                     "Use --model <model> or configure its model.")))
     (assoc options :provider provider :model model
            :messages (context/messages args (:prompt options))
            :actor (policy/actor args)))))

(defn complete-prompt
  [{:keys [provider model messages no-tools toolset actor on-progress]}]
  (if no-tools
    (if on-progress
      (ai/complete (dissoc provider :native-tools) model messages
                   {:on-progress on-progress})
      (ai/complete (dissoc provider :native-tools) model messages))
    (tools/complete provider model messages
                    (cond-> {:toolset toolset :actor actor}
                      on-progress (assoc :on-progress on-progress)))))

(defn prompt-cmd
  "ai <prompt> # ask the default AI provider; includes the conversation in Slack/Discord threads
   ai --provider <name> <prompt> # ask a named provider using its default model
   ai --provider <name> --model <model> <prompt> # choose a provider and model
   ai --model <model> <prompt> # choose a model on the default provider
   ai --no-tools <prompt> # answer without native or MCP tools"
  {:yb/cat #{:ai}}
  [args]
  (safely #(autodraft/complete (prepare-prompt args) args complete-prompt)))

(cmd-hook #"ai"
  #"^providers\s*$" providers-cmd
  #"^models(?:\s+(\S+))?\s*$" models-cmd
  #"^toolsets\s*$" toolsets-cmd
  #"^proposal\s+(\S+)(?:\s+(\d+))?\s*$" proposal-cmd
  #"^approve\s+(\S+)\s*$" approve-cmd
  #"^cancel\s+(\S+)\s*$" cancel-cmd
  #"^servers\s*$" servers-cmd
  #"^tools(?:\s+(\S+))?\s*$" tools-cmd
  #"(?s)^.*$" prompt-cmd)
