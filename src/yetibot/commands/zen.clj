(ns yetibot.commands.zen
  "OpenCode Zen discovery and bounded, single-hop text inference."
  (:require [yetibot.api.ai :as api]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.opencode :as opencode]
            [yetibot.commands.ai :as ai]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.models.ai-model-tools :as models]
            [yetibot.models.ai-toolsets :as toolsets]))

(def usage
  (str "Usage: zen [--model <model>] [--toolset <name>] <prompt>\n"
       "zen models # discover available OpenCode Zen models"))

(defn usage-cmd
  "zen # show OpenCode Zen usage without making an inference request"
  [_] usage)

(defn- failure [args unknown?]
  ;; Avoid core error decoration reflecting prompt prose or credentials.
  (when-let [skip (:skip-next-n args)]
    (reset! skip (count (:next-cmds args))))
  (let [message
        (if unknown?
          (str "OpenCode Zen outcome could not be confirmed; "
               "do not repeat this paid request.")
          (str "OpenCode Zen request did not run. Configure the zen AI "
               "provider, its API key and default model; check options "
               "and toolset access. Use zen models for available IDs."))]
    {:result/value message
     :result/data {:isError true :unknownOutcome (boolean unknown?)
                   :error message}}))

(defn- provider! []
  (let [provider (api/provider (api/config) "zen")]
    (when-not (opencode/provider? provider)
      (api/fail "Configure the OpenCode Zen provider first."))
    provider))

(defn models-cmd
  "zen models # discover all advertised OpenCode Zen model IDs"
  {:yb/cat #{:ai}}
  [{[_ options] :match :as args}]
  (try
    (when (and options (re-find #"\S" options))
      (api/fail "Usage: zen models"))
    (let [models (api/models (provider!))]
      {:result/value models
       :result/data {:provider "zen"
                     :models (mapv #(hash-map :id %) models)}
       :result/collection-path [:models]})
    (catch Exception _ (failure args false))))

(defn prompt-cmd
  "zen <prompt> # ask OpenCode Zen, text-only, no tools or implicit history
   zen --model <id> <prompt> # select any advertised Zen model
   zen --toolset <name> <prompt> # apply configured provider/model policy"
  {:yb/cat #{:ai}}
  [{:keys [match chat-source] :as args}]
  (try
    (let [options (ai/parse-prompt match usage)
          _ (when (or (:provider options) (:max-tool-calls options)
                      (:no-tools options))
              (api/fail usage))
          provider (provider!)
          _ (when-not (or (opencode/configured-key? (:key provider))
                          (:key-file provider))
              (api/fail "Configure the Zen API key first."))
          config (toolsets/select (mcp/config) (:toolset options))
          result (models/call!
                   config "ai"
                   (cond-> {"action" "ask" "provider" "zen"
                            "prompt" (:prompt options)}
                     (:model options) (assoc "model" (:model options)))
                   chat-source)
          data (:structuredContent result)]
      (if (:isError result)
        (failure args (:unknownOutcome result))
        {:result/value (:text data) :result/data data}))
    (catch Exception _ (failure args false))))

(cmd-hook #"zen"
  #"^\s*$" usage-cmd
  #"(?s)^models(?:\s+(.*))?$" models-cmd
  #"(?s)^.+$" prompt-cmd)
