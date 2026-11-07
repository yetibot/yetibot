(ns yetibot.commands.ai
  (:require [clojure.string :as string]
            [yetibot.api.ai :as ai]
            [yetibot.api.ai-outcome :as outcome]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.core.chat :as chat]
            [yetibot.core.models.admin :as admin]
            [yetibot.util.ai-public :as public-result]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-defaults :as defaults]
            [yetibot.models.ai-inspection :as inspection]
            [yetibot.models.ai-pricing :as pricing]
            [yetibot.models.ai-usage :as reports]
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
            [yetibot.util.ai-status :as status]
            [yetibot.util.ai-cost :as cost]
            [yetibot.util.ai-limits :as limits]))

(def usage
  (str "Usage: ai [--provider <name>] [--model <model>] "
       "[--toolset <name> | --no-tools] [--max-tool-calls <1..128>] "
       "[--max-output-tokens <1..262144>] <prompt>"))

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
                          "--max-tool-calls" :max-tool-calls
                          "--max-output-tokens" :max-output-tokens} flag)
                 [value rest-args] (when rest-args
                                     (string/split rest-args #"\s+" 2))]
             (when-not (and option (ai/nonblank-string? value)
                            (not (string/starts-with? value "--"))
                            (not (contains? options option)))
               (ai/fail usage))
             (let [value (if (contains? limits/defaults option)
                           (try (limits/request! option value)
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
     (let [result (f)]
       (when (get-in result [:result/data :isError])
         (when-let [skip (:skip-next-n args)]
           (reset! skip (count (:next-cmds args)))))
       result)
     (catch Exception e
       (let [data (ex-data e)
             report (:ai/tool-failure data)]
         (when (or report (:ai/piped-input data) (:ai/output-error data))
           (when-let [skip (:skip-next-n args)]
             (reset! skip (count (:next-cmds args)))))
         (cond
           report
           (public-result/reviewed
            {:result/value
            (str (trace/summary report (:ai/answers data))
                 (when-let [label (cost/accounting-label (:ai/accounting data))]
                   (str "\n\n-# " label)))
            :result/data
            (cond-> {:isError true :tool-trace report
                     :execution (trace/public-report report)}
              (:ai/accounting data) (assoc :ai/accounting (:ai/accounting data)))
             :result/public-data
             (cond-> {:execution (trace/public-report report)}
               (:ai/accounting data) (assoc :accounting (:ai/accounting data)))
             :result/public-error (trace/public-report report)})
           (:ai/piped-input data)
           ;; Core error decoration includes the evaluated command/input.
           {:result/value input/error-message
            :result/data {:isError true :error input/error-message}}
           (:ai/output-error data)
           (public-result/reviewed
            {:result/value
            (str (.getMessage e)
                 (when-let [label (cost/accounting-label (:ai/accounting data))]
                   (str "\n\n-# " label)))
            :result/data (merge {:isError true}
                                (select-keys data [:ai/response :ai/accounting]))
             :result/public-data
             {:response_stats (:ai/response data) :accounting (:ai/accounting data)}
             :result/public-error (:ai/response data)})
           :else
           (let [accounting (:ai/accounting data)
                 result
                 {:result/error
                  (str (if (:ai/error data)
                         (.getMessage e)
                         (str "The AI request failed. Check the provider "
                              "configuration and try again."))
                       (when-let [label (cost/accounting-label accounting)]
                         (str "\n\nModel requests: " label)))}]
             (if-not accounting result
               (public-result/reviewed
                 (assoc result
                        :result/data {:isError true :ai/accounting accounting}
                        :result/public-data {:accounting accounting}
                        :result/public-error
                        {:outcome "No usable terminal response; not retried."}))))))))))

(defn native-output-failure
  "Host-owned text adapter results only, never arbitrary remote MCP payloads."
  [args result]
  (when-let [message (get outcome/messages (:error-code result))]
    (safely args
      #(throw (ex-info message
                       (merge {:ai/error true
                               :ai/output-error (:error-code result)}
                              (select-keys (:structuredContent result)
                                           [:ai/cost :ai/accounting :ai/response])))))))

(defn native-text-result
  "Publish only accounting authored by the trusted native text adapter."
  [{:keys [structuredContent]}]
  (let [data structuredContent
        result {:result/value (:text data) :result/data data}]
    (if-not (:ai/accounting data) result
      (public-result/reviewed
        (assoc result :result/public-data
               {:accounting (:ai/accounting data)
                :request_cost (select-keys (:ai/cost data)
                                           [:usd :kind :scope :neurons])})))))

(defn usage-cmd
  "ai usage # current UTC month's recorded AI usage and cost subtotals
   ai usage YYYY-MM # a calendar month
   ai usage <start> <end> # dates/ISO instants; start inclusive, end exclusive"
  {:yb/cat #{:ai}}
  [{[_ text] :match :as args}]
  (let [result (safely args
                 #(let [data (reports/report! args text)]
                    (inspection/reviewed {:result/value (reports/readable data)
                                          :result/data data} data)))]
    (if-let [error (:result/error result)]
      ;; Core error decoration quotes the evaluated command. Never reflect
      ;; invalid private arguments, and stop rather than pipe an error into AI.
      (do
        (when-let [skip (:skip-next-n args)]
          (reset! skip (count (:next-cmds args))))
        (let [data {:isError true :error error}]
          (public-result/reviewed
            {:result/value error :result/data data :result/public-data data})))
      result)))

(defn default-cmd
  "ai default # inspect global runtime/configured defaults
   ai default <provider> <model> # global runtime change; shows list pricing
   ai default reset # restore file defaults; available to trusted chat users"
  {:yb/cat #{:ai}}
  [{[_ choice] :match :as args}]
  (safely args
    (fn []
      (let [parts (when-not (string/blank? choice)
                    (string/split (string/trim choice) #"\s+"))
            check! #(do (defaults/authorize! args)
                        (execution/require-direct! args))
            result
            (cond
              (empty? parts) (defaults/view)
              (= ["reset"] parts) (defaults/reset-default! check!)
              (= 2 (count parts))
              (defaults/set-default! (first parts) (second parts) check!)
              :else (ai/fail "Usage: ai default [<provider> <model> | reset]"))]
        (inspection/reviewed
          {:result/value
         (str "AI default: " (:provider result) " / "
              (or (:model result) (if (:model_policy result)
                                     "automatic verified-free" "not configured"))
              (when (:model_policy result) " (verified-free preference)")
              (if (:overridden result) " (runtime; resets on restart)"
                " (configured)")
              (when (:pricing result)
                (pricing/summary (:pricing result) (:previous result) result)))
         :result/data result} result)))))

(defn limits-cmd
  "ai limits # inspect effective global generation/tool budgets
   ai limits --max-output-tokens <n> --max-tool-calls <n> # runtime defaults
   ai limits reset # restore configured/built-in budgets; no inference"
  {:yb/cat #{:ai}}
  [{[_ choice] :match :as args}]
  (safely args
    (fn []
      (let [text (string/trim (or choice ""))
            usage (str "Usage: ai limits [reset | --max-output-tokens "
                       "<1..262144> | --max-tool-calls <1..128>]")
            check! #(do (defaults/authorize! args)
                        (execution/require-direct! args)
                        (when-not (admin/user-is-admin? (:user args))
                          (ai/fail "Changing global AI budgets requires an admin.")))
            result (cond
                     (string/blank? text) (limits/view)
                     (= text "reset") (limits/reset-runtime! check!)
                     :else
                     (let [options (parse-prompt text usage {} "limits")]
                       (when-not (and (= "limits" (:prompt options))
                                      (every? #{:prompt :max-output-tokens
                                                :max-tool-calls}
                                              (keys options)))
                         (ai/fail usage))
                       (limits/set-runtime! (dissoc options :prompt) check!)))
            values (:effective result)]
        (inspection/reviewed
          {:result/value
           (str "AI budgets: " (:generation_limit values)
                " tokens per model request · " (:tool_call_limit values)
                " tool calls per run. Global runtime overrides reset "
                "on restart; explicit flags win. Provider limits still apply.")
           :result/data result} result)))))

(defn providers-cmd
  "ai providers # list configured AI provider names"
  {:yb/cat #{:ai}}
  [_]
  (safely
    #(let [config (ai/config)
           selected (:name (ai/provider config nil))
           data (mapv (fn [p]
                        {:name (:name p) :default_model (:model p)
                         :selected (= selected (:name p))
                         :interface (or (:api p) "chat-completions")})
                      (:providers config))]
       (inspection/reviewed {:result/value (mapv :name data)
                             :result/data data} data))))

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
        (inspection/reviewed
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
                             providers results)}
          (into {}
            (map (fn [p result]
                   [(:name p)
                    (if (:result/error result) {:status "unavailable"}
                      (cond->
                        {:status "available"
                         :models (if-let [catalog (:catalog result)]
                                   (mapv #(select-keys % [:id :interface])
                                         (:models catalog))
                                   (mapv (fn [id] {:id id}) (:models result)))}
                        (:catalog result)
                        (assoc :catalog (select-keys (:catalog result)
                                                     [:source :coverage]))))])
                 providers results)))))))

(defn servers-cmd
  "ai servers # list configured MCP and native tool sources"
  {:yb/cat #{:ai}}
  [args]
  (safely
    #(let [data (tools/server-info (policy/actor args))]
       (inspection/reviewed {:result/value (mapv :name data)
                             :result/data data} data))))

(defn- help-text [redact value]
  (-> (str (or value ""))
      (string/replace #"\s+" " ")
      (string/replace #"@(?=everyone\b|here\b)" "@\u200b")
      (string/replace #"<(?=[@!])" "<\u200b")
      string/trim
      redact))

(defn- help-markdown [redact value]
  (let [text (-> (str (or value ""))
                 (string/replace #"\r\n?" "\n")
                 (string/replace #"@(?=everyone\b|here\b)" "@\u200b")
                 (string/replace #"<(?=[@!])" "<\u200b")
                 string/trim
                 redact)
        flat (string/replace text #"\s+" " ")
        safe-flat (redact flat)]
    ;; Fail closed when whitespace folding reconstructs a configured secret.
    ;; Ordinary provider Markdown keeps its paragraphs, code and punctuation.
    (if (= flat safe-flat) text safe-flat)))

(defn- tool-overview [redact id description]
  (if (string/blank? description)
    "No description provided."
    (let [paragraph (first (string/split (help-markdown redact description)
                                         #"\n[ \t]*\n" 2))
          text (help-text redact paragraph)
          purpose (or (second (re-find #"(?s)^(.+?[.!?])(?:\s|$)" text)) text)]
      (if (or (> (count purpose) 240)
              (re-find #"^(?:`{3,}|~{3,}|interface\s|declare\s|async\s|//)" text))
        (str "Full documentation: ai tools " id ".")
        purpose))))

(defn- schema-field [schema field]
  (get schema field (get schema (name field))))

(defn- parameter-name [field]
  (if (keyword? field) (subs (str field) 1) field))

(defn- tool-summary [redact {:keys [id description]}]
  ;; Xargs parses its first row to classify the command. Keep prose literal.
  (str "`" (string/escape id {\` \’ \| \¦ \newline \space
                             \return \space})
       "` — " (string/escape (tool-overview redact id description)
                              {\| \¦ \` \’ \$ \＄ \" \”})))

(defn- tool-detail [redact {:keys [id description inputSchema]}]
  (let [display-text (partial help-text redact)
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
              (help-markdown redact description)) "Inputs:"]
        (if (seq parameters)
          (map (fn [[field schema]]
                 (let [field (parameter-name field)
                       type (schema-field schema :type)
                       enum (schema-field schema :enum)
                       description (schema-field schema :description)]
                   (str "  " (display-text field)
                        (when (required field) " (required)") ": "
                        (display-text (if (vector? type)
                                     (string/join " | " type)
                                     (or type "any")))
                        (when (seq enum)
                          (str " [" (display-text (string/join ", " enum))
                               "]"))
                        (when (seq description)
                          (str " — " (help-markdown redact description))))))
               parameters)
          ["  No parameter details advertised."])
        (when (seq variants)
          [(str "Required alternatives: "
                (display-text
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
                         variants))))])))))

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

(defn- public-tool-data [redact {:keys [id description inputSchema]} detail?]
  (cond-> {:id (help-text redact id)
           :summary (tool-overview redact (help-text redact id) description)}
    detail?
    (assoc :parameters
      (let [required (set (schema-field inputSchema :required))]
        (mapv (fn [[field schema]]
                (let [field (parameter-name field)
                      type (schema-field schema :type)]
                  {:name (help-text redact field)
                   :type (help-text redact
                           (cond
                             (string? type) type
                             (and (vector? type) (every? string? type))
                             (string/join " | " type)
                             :else "any"))
                   :required (boolean (required field))}))
              (sort-by (fn [[field _]]
                         [(not (required (parameter-name field)))
                          (parameter-name field)])
                       (schema-field inputSchema :properties)))))))

(defn- reviewed-tool-text [redact result]
  ;; Review only human text and this allowlisted metadata projection. Raw
  ;; catalogs/schemas remain pipeline-only; the existing viewer bounds apply.
  (let [value (:result/value result)
        data (:result/data result)
        public-data (when data
                      (if (:result/collection-path result)
                        (when (seq (:tools data))
                          (mapv #(public-tool-data redact % false) (:tools data)))
                        (public-tool-data redact data true)))]
    (public-result/reviewed
      (cond-> (assoc result :result/public-text
                (if (string? value) value (string/join "\n" value)))
        public-data (assoc :result/public-data public-data)))))

(defn tools-cmd
  "ai tools # browse allowed tools with one-sentence overviews
   ai tools <server> # list allowed tools from one source
   ai tools <server/tool> # read full formatted docs and inputs; list rows work
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
        (reviewed-tool-text redact
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
             :result/collection-path [:tools]}))))))

(defn toolsets-cmd
  "ai toolsets # list toolsets available to you in this conversation"
  {:yb/cat #{:ai}}
  [args]
  (safely
    #(let [names (tools/toolset-names (policy/actor args))
           data (mapv (fn [name] {:name name}) names)]
       (inspection/reviewed {:result/value names :result/data data} data))))

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
         automatic? (and (= "zen" (:name provider))
                         (or (nil? (:model provider))
                             (:ai/free-default provider))
                         (not (:model options)))
         t (when (and automatic? (not (:no-tools options)))
             (:toolset (toolsets/select (mcp/config) (:toolset options))))
         actor (policy/actor args)
         _ (when automatic? (policy/authorize-provider! t actor provider))
         model (or (:model options)
                   (ai/default-model provider
                     #(policy/model-allowed? t actor provider %)))
         provider (cond-> provider automatic? (assoc :ai/verified-free true))]
     (when-not model
       (ai/fail (str "No default model configured for this provider. "
                     "Use --model <model> or configure its model.")))
     (cond-> (assoc (merge options
                          (limits/effective
                            (merge (when-not (:no-tools options)
                                     (select-keys (mcp/config)
                                                  [:max-tool-calls]))
                                   config)
                            options))
                    :provider provider :model model
                    :caption? (empty? (:next-cmds args))
                    :messages (context/messages args (:prompt options))
                    :actor (policy/actor args))
       snapshot (assoc :pipeline-input snapshot
                       :additional-secrets (keep :key (:providers config)))))))

(defn prepare-followup
  "Re-resolve a host-owned route; the body is data, never flags or a pipeline."
  [args route resolve-config]
  (let [provider (ai/provider (resolve-config) (:provider route))
        _ (execution/ensure-followup! route provider)
        actor (policy/actor args)
        _ (when-not actor
            (ai/fail "AI follow-ups require a trusted chat user."))
        _ (when (:verified-free route)
            (policy/authorize!
              (when-not (:no-tools route)
                (:toolset (toolsets/select (mcp/config) (:toolset route))))
              actor provider (:model route)))
        provider (execution/revalidate-free-followup! route provider)
        messages (context/messages args (:match args))]
    (execution/ensure-followup! route provider)
    (merge (select-keys route [:no-tools :toolset :max-tool-calls :max-output-tokens])
           {:provider provider :model (:model route) :messages messages
            :actor actor :prompt (:match args) :caption? true})))

(defn- complete-prompt*
  [{:keys [provider model messages no-tools toolset actor on-progress
           pipeline-input additional-secrets max-tool-calls max-output-tokens]}]
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
                     {:on-progress on-progress :max-output-tokens max-output-tokens})
        (ai/complete (dissoc provider :native-tools) model messages
                     {:max-output-tokens max-output-tokens})))
    (tools/complete provider model messages
                    (cond-> {:toolset toolset :actor actor
                             :max-output-tokens max-output-tokens}
                      max-tool-calls (assoc :max-tool-calls max-tool-calls)
                      pipeline-input (assoc :pipeline-input pipeline-input
                                            :additional-secrets
                                            additional-secrets)
                      on-progress (assoc :on-progress on-progress)))))

(defn complete-prompt [prepared]
  (let [budgets (limits/effective prepared)
        result (complete-prompt* (merge prepared budgets))]
    (if-not (map? result) result
      (public-result/reviewed
        (-> result
            (assoc-in [:result/data :ai/limits] (limits/public-values budgets))
            (assoc-in [:result/public-data :limits]
                      (limits/public-values budgets)))))))

(defn prompt-cmd
  {:doc (str "ai <prompt> # ask the default AI provider; "
             "includes the conversation in Slack/Discord threads\n"
             "   ai --provider <name> <prompt> # ask a named provider "
             "using its default model\n"
             "   ai --provider <name> --model <model> <prompt> # "
             "choose a provider and model\n"
             "   ai --model <model> <prompt> # infer a unique provider "
             "by exact advertised model ID\n"
             "   ai --max-tool-calls <1..128> <prompt> # override the "
             "host-call budget\n"
             "   ai --max-output-tokens <1..262144> <prompt> # override "
             "generation budgets for parent and child models\n"
             "   ai limits # inspect/set/reset global runtime budgets\n"
             "   ai --no-tools <prompt> # answer without native or MCP tools")
   :yb/cat #{:ai}}
  [args]
  (safely args
    #(status/with-status
       chat/*target* (prepare-prompt args)
       (fn [prepared]
         (autodraft/complete prepared args
           (fn [selected]
             (execution/remember-answer! "ai" selected
                                         (complete-prompt selected))))))))

(defmethod execution/run-followup "ai" [route args]
  (safely args
    #(status/with-status
       chat/*target* (prepare-followup args route ai/config)
       (fn [prepared]
         (execution/remember-answer! "ai" prepared
                                     (complete-prompt prepared))))))

(cmd-hook #"ai"
  #"(?s)^usage(?:\s+(.*))?\s*$" usage-cmd
  #"^default(?:\s+(.*))?\s*$" default-cmd
  #"(?s)^limits(?:\s+(.*))?\s*$" limits-cmd
  #"^providers\s*$" providers-cmd
  #"^models(?:\s+(\S+))?\s*$" models-cmd
  #"^toolsets\s*$" toolsets-cmd
  #"^proposal\s+(\S+)(?:\s+(\d+))?\s*$" proposal-cmd
  #"^approve\s+(\S+)\s*$" approve-cmd
  #"^cancel\s+(\S+)\s*$" cancel-cmd
  #"^servers\s*$" servers-cmd
  #"(?s)^tools(?:\s+(.+?))?\s*$" tools-cmd
  #"(?s)^.*$" prompt-cmd)
