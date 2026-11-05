(ns yetibot.models.ai-defaults
  "Trusted-user global runtime defaults; no file or credential mutation."
  (:require [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.opencode :as zen]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-pricing :as pricing]
            [yetibot.util.ai-execution :as execution]))

(defn authorize! [event]
  (execution/sensitive!)
  (when-not (and (policy/actor event)
                 (ai/nonblank-string? (get-in event [:chat-source :room])))
    (ai/fail "Changing global AI defaults requires a trusted chat request.")))

(defn- public-choice [config choice]
  (let [secrets (mapcat (juxt :key :key-file) (:providers config))]
    (into {} (map (fn [[k v]]
                    [k (if (string? v)
                         (mcp/metadata-redact v {:secrets secrets}) v)])
                  choice))))

(defn view []
  (let [config (ai/config) p (ai/provider config nil)]
    (public-choice config
      (cond-> {:provider (:name p) :model (:model p)
               :scope "global" :runtime_only true
               :overridden (boolean @ai/runtime-default)}
        (and (= "zen" (:name p))
             (or (nil? (:model p)) (:ai/free-default p)))
        (assoc :model_policy "fresh_verified_free_preference")))))

(defn- comparison-result [{:keys [before after previous current prices]}]
  ;; Capture identities atomically. Optional prices were fetched before the
  ;; final authority check; an outage means unavailable, never a fake zero.
  (assoc after :previous before
         :pricing (pricing/comparison previous current prices)))

(defn set-default!
  "check! revalidates trusted caller/policy immediately before the one write."
  [provider-name model check!]
  (check!)
  (when-not (and (string? model)
                 (re-matches #"[A-Za-z0-9@._:/-]{1,200}" model))
    (ai/fail "Use an exact model ID with ai default <provider> <model>."))
  (let [config (ai/config) p (ai/provider config provider-name)
        resolved (ai/resolve-provider p)
        secrets (concat (mapcat (juxt :key :key-file) (:providers config))
                        [(:key resolved)])]
    (when-not (every? #(= % (mcp/metadata-redact % {:secrets secrets}))
                      [(:name p) model])
      (ai/fail "AI defaults cannot contain configured credentials."))
    (let [prices (pricing/catalog (:providers config))]
      (comparison-result
        (locking ai/runtime-default
          (check!)
          (let [now (ai/config) previous (ai/provider now nil)
                before (view)]
            (when-not (= (dissoc p :model :ai/free-default)
                         (dissoc (ai/provider now provider-name)
                                 :model :ai/free-default))
              (ai/fail
                "AI provider configuration changed. Set the default again."))
            (reset! ai/runtime-default {:provider (:name p) :model model})
            {:before before :after (view) :previous previous :prices prices
             :current (ai/provider (ai/config) nil)}))))))

(defn reset-default! [check!]
  (check!)
  (let [prices (pricing/catalog (:providers (ai/config)))]
    (comparison-result
      (locking ai/runtime-default
        (check!)
        (let [before (view) previous (ai/provider (ai/config) nil)]
          (reset! ai/runtime-default nil)
          ;; Reset the former Zen-only preference as well as the global choice.
          (reset! zen/preferences {})
          {:before before :after (view) :previous previous :prices prices
           :current (ai/provider (ai/config) nil)})))))
