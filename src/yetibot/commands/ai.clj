(ns yetibot.commands.ai
  (:require [clojure.string :as string]
            [yetibot.api.ai :as ai]
            [yetibot.core.hooks :refer [cmd-hook]]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-tools :as tools]))

(def usage "Usage: ai [--provider <name>] [--model <model>] [--no-tools] <prompt>")

(defn parse-prompt
  "Only leading flags are options. Use -- to start a prompt with a flag."
  [args]
  (loop [remaining (string/trim args) options {}]
    (cond
      (string/blank? remaining) (ai/fail usage)
      (string/starts-with? remaining "--")
      (let [[flag rest-args] (string/split remaining #"\s+" 2)]
        (cond
          (= "--" flag)
          (if (string/blank? rest-args)
            (ai/fail usage)
            (assoc options :prompt rest-args))
          (= "--no-tools" flag)
          (if (:no-tools options)
            (ai/fail usage)
            (recur (or rest-args "") (assoc options :no-tools true)))
          :else
          (let [option ({"--provider" :provider "--model" :model} flag)
                [value rest-args] (when rest-args (string/split rest-args #"\s+" 2))]
            (when-not (and option (ai/nonblank-string? value)
                           (not (string/starts-with? value "--"))
                           (not (contains? options option)))
              (ai/fail usage))
            (recur (or rest-args "") (assoc options option value)))))
      :else (assoc options :prompt remaining))))

(defn- safely [f]
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
                            (safely #(hash-map :models (ai/models provider))))
                          providers)]
        {:result/data (zipmap (map :name providers) results)
         :result/value (mapv (fn [provider result]
                               (str (:name provider) ": "
                                    (or (:result/error result)
                                        (when (seq (:models result))
                                          (string/join ", " (:models result)))
                                        "No models available.")))
                             providers results)}))))

(defn servers-cmd
  "ai servers # list configured MCP server names"
  {:yb/cat #{:ai}}
  [_]
  (safely tools/server-names))

(defn tools-cmd
  "ai tools # list allowed tools from configured MCP servers
   ai tools <server> # list allowed tools from one MCP server"
  {:yb/cat #{:ai}}
  [{[_ server-name] :match}]
  (safely #(tools/tool-names server-name)))

(defn prompt-cmd
  "ai <prompt> # ask the default AI provider; includes the conversation in Slack/Discord threads
   ai --provider <name> <prompt> # ask a named provider using its default model
   ai --provider <name> --model <model> <prompt> # choose a provider and model
   ai --model <model> <prompt> # choose a model on the default provider
   ai --no-tools <prompt> # answer without connecting to MCP servers"
  {:yb/cat #{:ai}}
  [{:keys [match] :as args}]
  (safely
    (fn []
      (let [{:keys [provider model prompt no-tools]} (parse-prompt match)
            provider (ai/provider (ai/config) provider)
            model (or model (:model provider))]
        (when-not model
          (ai/fail "No default model configured for this provider. Use --model <model> or configure its model."))
        ((if no-tools ai/complete tools/complete)
         provider model (context/messages args prompt))))))

(cmd-hook #"ai"
  #"^providers\s*$" providers-cmd
  #"^models(?:\s+(\S+))?\s*$" models-cmd
  #"^servers\s*$" servers-cmd
  #"^tools(?:\s+(\S+))?\s*$" tools-cmd
  #"(?s)^.*$" prompt-cmd)
