(ns yetibot.commands.cf
  "Cloudflare shortcut: catalog discovery and bounded text-only inference."
  (:require [clojure.string :as string]
            [yetibot.api.ai :as api]
            [yetibot.api.cloudflare :as cloudflare]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as ai]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.models.ai-model-tools :as models]
            [yetibot.models.ai-toolsets :as toolsets]))

(def usage "Usage: cf [--model <model>] [--toolset <name>] <prompt>")

(defn- failure [args unknown?]
  ;; Core decorates :result/error with the entire evaluated command. Return
  ;; only static human text and safe structured flags, never that error path.
  ;; Keep failure short-circuiting, especially after an uncertain paid POST.
  (when-let [skip (:skip-next-n args)]
    (reset! skip (count (:next-cmds args))))
  (let [message (if unknown?
                  (str "Cloudflare outcome could not be confirmed; "
                       "do not repeat this paid request.")
                  (str "Cloudflare request failed. Check options, access, "
                       "configuration and chat model eligibility."))]
    {:result/value message
     :result/data {:isError true :unknownOutcome (boolean unknown?)
                   :error message}}))

(defn- safely [args f]
  (try (f) (catch Exception _ (failure args false))))

(defn models-cmd
  "cf models # list the full Workers AI catalog with task/interface labels"
  {:yb/cat #{:ai}}
  [args]
  (safely args
    (fn []
      (let [provider (api/provider (api/config) "cloudflare")]
        (when-not (cloudflare/provider-account provider)
          (api/fail "Configure the Cloudflare Workers AI provider first."))
        (let [models (api/models provider)
              catalog (:catalog (meta models))]
          {:result/data catalog
           :result/value
           (str (:coverage catalog) "\n"
                (string/join
                  "\n" (map #(str (:id %) " ["
                                  (get-in % [:task :name] "unknown task")
                                  "; " (name (:interface %)) "]")
                            (:models catalog))))})))))

(defn prompt-cmd
  "cf <prompt> # ask Cloudflare, text-only, no tools or implicit history
   cf --model <model> <prompt> # choose a catalogued chat model
   cf --toolset <name> <prompt> # enforce a configured provider/model policy"
  {:yb/cat #{:ai}}
  [{:keys [match chat-source] :as args}]
  (safely args
    (fn []
      (let [options (ai/parse-prompt match usage)
            _ (when (:provider options) (api/fail usage))
            config (toolsets/select (mcp/config) (:toolset options))
            result (models/call!
                     config "cf"
                     (cond-> {"action" "ask" "prompt" (:prompt options)}
                       (:model options) (assoc "model" (:model options)))
                     chat-source)
            data (:structuredContent result)]
        (if (:isError result)
          (failure args (:unknownOutcome result))
          {:result/value (:text data) :result/data data})))))

(cmd-hook #"cf"
  #"^models\s*$" models-cmd
  #"(?s)^.+$" prompt-cmd)
