(ns yetibot.util.ai-limits
  "Host-owned generation and tool budgets; runtime overrides hold numbers only."
  (:require [yetibot.core.config :refer [get-config]]
            [yetibot.util.ai-config :as settings]))

(def defaults {:max-output-tokens 32768 :max-tool-calls 20})
(def ceilings {:max-output-tokens 262144 :max-tool-calls 128})
(defonce runtime (atom {}))

(defn request!
  "Strict decimal integers, including environment/CLI strings; nil is omitted."
  [field value]
  (when (some? value)
    (let [maximum (get ceilings field)
          text (str value)
          n (when (and maximum (re-matches #"[1-9][0-9]{0,5}" text))
              (Long/parseLong text))]
      (when-not (and n (<= n maximum))
        (throw (ex-info (str "AI " (name field)
                            " must be an integer from 1 to " maximum ".")
                        {:ai/error true :ai/request-not-sent true})))
      n)))

(defn- configured []
  (let [{:keys [value error]} (get-config map? [:ai])]
    (when (and error (not= :not-found error))
      (throw (ex-info "AI budget configuration is invalid."
                      {:ai/error true :ai/request-not-sent true})))
    (let [ai (update (settings/ai value) :mcp settings/mcp)]
      (cond-> (select-keys ai (keys defaults))
        (and (not (contains? ai :max-tool-calls))
             (get-in ai [:mcp :max-tool-calls]))
        (assoc :max-tool-calls (get-in ai [:mcp :max-tool-calls]))))))

;; Core file/environment config is itself fixed at startup. Cache only budget
;; fields, not provider records, so wire calls need no configuration/logging IO.
(defonce ^:private configured-defaults (configured))

(defn effective
  ([options] (effective configured-defaults options))
  ([config options]
   (let [config (cond-> config
                  (and (not (contains? config :max-tool-calls))
                       (get-in config [:mcp :max-tool-calls]))
                  (assoc :max-tool-calls (get-in config [:mcp :max-tool-calls])))
         values (merge defaults (select-keys config (keys defaults))
                       @runtime (select-keys options (keys defaults)))]
     (into {} (map (fn [[field value]]
                     (let [n (request! field value)]
                       (when-not n
                         (throw (ex-info "AI budget cannot be empty."
                                         {:ai/error true
                                          :ai/request-not-sent true})))
                       [field n])) values)))))

(defn public-values [values]
  {:generation_limit (:max-output-tokens values)
   :tool_call_limit (:max-tool-calls values)})

(defn view []
  {:scope "global" :runtime_only true
   :effective (public-values (effective {}))
   :runtime_overrides (into {} (remove (comp nil? val))
                            (public-values @runtime))
   :ceilings (public-values ceilings)})

(defn set-runtime! [values check!]
  (check!)
  (when-not (and (seq values) (every? (set (keys defaults)) (keys values)))
    (throw (ex-info "Use only AI generation and tool-call budget settings."
                    {:ai/error true})))
  ;; Validate the entire update before changing either value. Merge under the
  ;; lock so simultaneous partial updates cannot overwrite unrelated fields.
  (let [requested (select-keys (effective values) (keys values))]
    (locking runtime
      (check!)
      (swap! runtime merge requested)
      (view))))

(defn reset-runtime! [check!]
  (check!)
  (locking runtime
    (check!)
    (reset! runtime {})
    (view)))
