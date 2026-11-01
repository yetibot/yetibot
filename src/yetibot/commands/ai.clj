(ns yetibot.commands.ai
  (:require [clojure.string :as string]
            [yetibot.api.ai :as ai]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.chat :as chat]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-input :as input]
            [yetibot.models.ai-provider-catalog :as provider-catalog]
            [yetibot.models.ai-autodraft :as autodraft]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-tool-trace :as trace]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.models.ai-github :as github]
            [yetibot.util.ai-execution :as execution]
            [yetibot.util.ai-status :as status]))

(def usage
  (str "Usage: ai [--provider <name>] [--model <model>] "
       "[--toolset <name> | --no-tools] [--max-tool-calls <1..20>] <prompt>"))

(defn parse-prompt
  "Only leading flags are options. Use -- to start a prompt with a flag."
  ([args] (parse-prompt args usage))
  ([args usage] (parse-prompt args usage {}))
  ([args usage switches] (parse-prompt args usage switches nil))
  ([args usage switches default-prompt]
   (loop [remaining (string/trim args) options {}]
     (cond
       (string/blank? remaining)
       (if (and default-prompt
                (not (and (:no-tools options)
                          (or (:toolset options) (:max-tool-calls options)))))
         (assoc options :prompt default-prompt)
         (ai/fail usage))
       (string/starts-with? remaining "--")
       (let [[flag rest-args] (string/split remaining #"\s+" 2)
             switch (get (assoc switches "--no-tools" :no-tools) flag)]
         (cond
           (= "--" flag)
           (if (string/blank? rest-args)
             (ai/fail usage)
             (do
               (when (and (:no-tools options)
                          (or (:toolset options) (:max-tool-calls options)))
                 (ai/fail usage))
               (assoc options :prompt rest-args)))
           switch
           (if (contains? options switch)
             (ai/fail usage)
             (recur (or rest-args "") (assoc options switch true)))
           :else
           (let [option ({"--provider" :provider "--model" :model
                          "--toolset" :toolset
                          "--max-tool-calls" :max-tool-calls} flag)
                 [value rest-args] (when rest-args
                                     (string/split rest-args #"\s+" 2))]
             (when-not (and option (ai/nonblank-string? value)
                            (not (string/starts-with? value "--"))
                            (not (contains? options option)))
               (ai/fail usage))
             (let [value (if (= option :max-tool-calls)
                           (try (tools/request-budget! value)
                                (catch Exception _ (ai/fail usage))) value)]
               (recur (or rest-args "") (assoc options option value))))))
       :else (do
               (when (and (:no-tools options)
                          (or (:toolset options) (:max-tool-calls options)))
                 (ai/fail usage))
               (assoc options :prompt remaining))))))

(defn safely
  ([f] (safely nil f))
  ([args f]
   (try
     (f)
     (catch Exception e
       (let [data (ex-data e)
             report (:ai/tool-failure data)]
         (when (or report (:ai/piped-input data))
           (when-let [skip (:skip-next-n args)]
             (reset! skip (count (:next-cmds args)))))
         (cond
           report
           {:result/value (trace/summary report)
            :result/data {:isError true :tool-trace report}}
           (:ai/piped-input data)
           ;; Core error decoration includes the evaluated command/input.
           {:result/value input/error-message
            :result/data {:isError true :error input/error-message}}
           :else
           {:result/error
            (if (:ai/error (ex-data e))
              (.getMessage e)
              (str "The AI request failed. Check the provider configuration "
                   "and try again."))}))))))

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

(defn- help-text [redact value limit]
  (let [text (-> (str (or value ""))
                 (string/replace #"\s+" " ")
                 (string/replace #"@(?=everyone\b|here\b)" "@\u200b")
                 (string/replace #"<(?=[@!])" "<\u200b")
                 string/trim
                 redact)]
    (if (> (count text) limit)
      (str (subs text 0 (dec limit)) "…")
      text)))

(defn- schema-field [schema field]
  (get schema field (get schema (name field))))

(defn- parameter-name [field]
  (if (keyword? field) (subs (str field) 1) field))

(defn- tool-summary [redact {:keys [id description]}]
  ;; Xargs parses its first row to classify the command. Keep prose literal.
  (str "`" (string/escape id {\` \’ \| \¦ \newline \space
                             \return \space})
       "` — " (if (string/blank? description)
                   "No description provided."
                   (help-text redact
                     (string/escape description
                                    {\| \¦ \` \’ \$ \＄ \" \”}) 120))))

(defn- tool-detail [redact {:keys [id description inputSchema]}]
  (let [short-text (partial help-text redact)
        properties (schema-field inputSchema :properties)
        required (set (schema-field inputSchema :required))
        parameters (sort-by (fn [[field _]]
                              [(not (required (parameter-name field)))
                               (parameter-name field)]) properties)
        variants (schema-field inputSchema :oneOf)]
    (string/join
      "\n"
      (concat
        [id (if (string/blank? description) "No description provided."
              (short-text description 500)) "Inputs:"]
        (if (seq parameters)
          (map (fn [[field schema]]
                 (let [field (parameter-name field)
                       type (schema-field schema :type)
                       enum (schema-field schema :enum)
                       description (schema-field schema :description)]
                   (str "  " (short-text field 60)
                        (when (required field) " (required)") ": "
                        (short-text (if (vector? type)
                                     (string/join " | " type)
                                     (or type "any")) 80)
                        (when (seq enum)
                          (str " [" (short-text (string/join ", " enum) 100)
                               "]"))
                        (when (seq description)
                          (str " — " (short-text description 120))))))
               (take 20 parameters))
          ["  No parameter details advertised."])
        (when (> (count parameters) 20)
          ["  … Use data show for the full schema."])
        (when (seq variants)
          [(str "Required alternatives: "
                (short-text
                  (string/join
                    "; "
                    (map (fn [variant]
                           (let [action (schema-field
                                          (schema-field variant :properties)
                                          :action)
                                 label (first (schema-field action :enum))]
                             (str (when label (str label ": "))
                                  (string/join ", "
                                    (schema-field variant :required)))))
                         variants)) 500))])))))

(defn- empty-catalog-text [catalog]
  (let [servers (get-in catalog [:discovery :servers])]
    (cond
      (some #(or (= "pending" (:status %)) (:refreshing %)) servers)
      (str "Tool discovery is refreshing in the background. "
           "No allowed tools are available yet.")
      (some #(= "unavailable" (:status %)) servers)
      (str "This MCP source is temporarily unavailable. "
           "Other tool sources are unaffected.")
      :else "No allowed tools are currently advertised.")))

(defn tools-cmd
  "ai tools # list allowed tools with short descriptions
   ai tools <server> # list allowed tools from one source
   ai tools <server/tool> # inspect one tool; list rows also work
   ai tools <tool> # inspect a unique tool name"
  {:yb/cat #{:ai}}
  [{[_ selector] :match :as args}]
  (safely
    (fn []
      (let [selector (when selector
                       (let [id (string/trim
                                  (first (string/split selector #" — " 2)))]
                         (not-empty
                           (if (and (string/starts-with? id "`")
                                    (string/ends-with? id "`")
                                    (> (count id) 1))
                             (subs id 1 (dec (count id))) id))))
            actor (policy/actor args)
            servers (when selector (tools/server-names actor))
            server (some #{selector} servers)
            source (or server
                       (some #(when (string/starts-with? selector (str % "/"))
                                %)
                             (sort-by count > servers)))
            catalog (tools/tool-catalog source actor)
            redactions (mcp/config)
            redact #(mcp/metadata-redact % redactions)]
        (if (and selector (not server))
          (let [matches (filterv #(or (= selector (:id %))
                                     (= selector (:name %))) (:tools catalog))]
            (case (count matches)
              0 (ai/fail (str "Unknown or unavailable AI tool or server. "
                             "Use ai tools to list tools."))
              1 {:result/value (tool-detail redact (first matches))
                 :result/data (first matches)}
              (ai/fail "Tool name is ambiguous. Use its server/name ID.")))
          {:result/value (cond
                           (string? (:names catalog)) (:names catalog)
                           (seq (:tools catalog))
                           (mapv (partial tool-summary redact)
                                 (:tools catalog))
                           :else (empty-catalog-text catalog))
           :result/data (dissoc catalog :names)
           :result/collection-path [:tools]})))))

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
   (let [snapshot (input/capture args)
         options (parse-prompt (if snapshot (input/command-text args) match)
                               prompt-usage switches
                               (when snapshot
                                 "Respond to the supplied Yetibot input."))
         _ (when (and command-provider (:provider options))
             (ai/fail prompt-usage))
         config (resolve-config)
         provider-name (or command-provider (:provider options)
                           (when (and (:model options)
                                      (> (count (:providers config)) 1))
                             (provider-catalog/infer-provider
                               provider-catalog/shared-cache config
                               (:model options))))
         _ (when (and command-provider
                      (not-any? #(= provider-name (:name %))
                                (:providers config)))
             (ai/fail (str "AI provider " provider-name
                           " is not configured. Add it to ai.providers.")))
         provider (ai/provider config provider-name)
         automatic? (and (= "zen" (:name provider)) (not (:model options)))
         t (when (and automatic? (not (:no-tools options)))
             (:toolset (toolsets/select (mcp/config) (:toolset options))))
         actor (policy/actor args)
         _ (when automatic? (policy/authorize-provider! t actor provider))
         model (or (:model options)
                   (ai/default-model provider
                     #(policy/model-allowed? t actor provider %)))]
     (when-not model
       (ai/fail (str "No default model configured for this provider. "
                     "Use --model <model> or configure its model.")))
     (cond-> (assoc options :provider provider :model model
                    :caption? (empty? (:next-cmds args))
                    :messages (context/messages args (:prompt options))
                    :actor (policy/actor args))
       snapshot (assoc :pipeline-input snapshot
                       :additional-secrets (keep :key (:providers config)))))))

(defn complete-prompt
  [{:keys [provider model messages no-tools toolset actor on-progress
           pipeline-input additional-secrets max-tool-calls]}]
  (if no-tools
    (let [credential-file (:key-file provider)
          provider (if pipeline-input (ai/resolve-provider provider) provider)
          redactions (when pipeline-input
                       (assoc (try (mcp/config) (catch Exception _ {}))
                              :secrets (concat [(:key provider) credential-file]
                                               additional-secrets)))
          messages (tools/host-messages
                     (input/messages messages pipeline-input redactions))]
      (if on-progress
        (ai/complete (dissoc provider :native-tools) model messages
                     {:on-progress on-progress})
        (ai/complete (dissoc provider :native-tools) model messages)))
    (tools/complete provider model messages
                    (cond-> {:toolset toolset :actor actor}
                      max-tool-calls (assoc :max-tool-calls max-tool-calls)
                      pipeline-input (assoc :pipeline-input pipeline-input
                                            :additional-secrets
                                            additional-secrets)
                      on-progress (assoc :on-progress on-progress)))))

(defn prompt-cmd
  {:doc (str "ai <prompt> # ask the default AI provider; "
             "includes the conversation in Slack/Discord threads\n"
             "   ai --provider <name> <prompt> # ask a named provider "
             "using its default model\n"
             "   ai --provider <name> --model <model> <prompt> # "
             "choose a provider and model\n"
             "   ai --model <model> <prompt> # infer a unique provider "
             "by exact advertised model ID\n"
             "   ai --max-tool-calls <1..20> <prompt> # choose a total "
             "host-call budget within the configured ceiling\n"
             "   ai --no-tools <prompt> # answer without native or MCP tools")
   :yb/cat #{:ai}}
  [args]
  (safely args
    #(status/with-status
       chat/*target* (prepare-prompt args)
       (fn [prepared]
         (autodraft/complete prepared args complete-prompt)))))

(cmd-hook #"ai"
  #"^providers\s*$" providers-cmd
  #"^models(?:\s+(\S+))?\s*$" models-cmd
  #"^toolsets\s*$" toolsets-cmd
  #"^proposal\s+(\S+)(?:\s+(\d+))?\s*$" proposal-cmd
  #"^approve\s+(\S+)\s*$" approve-cmd
  #"^cancel\s+(\S+)\s*$" cancel-cmd
  #"^servers\s*$" servers-cmd
  #"(?s)^tools(?:\s+(.+?))?\s*$" tools-cmd
  #"(?s)^.*$" prompt-cmd)
