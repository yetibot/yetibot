(ns yetibot.models.ai-defaults
  "Admin-only global runtime defaults; no file or credential mutation."
  (:require [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.opencode :as zen]
            [yetibot.core.models.admin :as admin]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.util.ai-execution :as execution]))

(defn authorize! [event]
  (execution/sensitive!)
  (let [actor (policy/actor event)]
    (when-not (and actor (admin/user-is-admin? {:id (:user actor)}))
      (ai/fail "Changing global AI defaults requires a trusted admin request."))))

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
        (= "zen" (:name p))
        (assoc :model_policy "fresh_verified_free_preference")))))

(defn set-default!
  "check! revalidates caller/policy after catalog IO and before the one write."
  [provider-name model check!]
  (check!)
  (when-not (and (string? model)
                 (re-matches #"[A-Za-z0-9@._:/-]{1,200}" model))
    (ai/fail "Use an exact model ID with ai default <provider> <model>."))
  (let [config (ai/config) p (ai/provider config provider-name)
        resolved (ai/resolve-provider p)
        secrets (concat (mapcat (juxt :key :key-file) (:providers config))
                        [(:key resolved)])
        free? (= "zen" (:name p))]
    (when-not (every? #(= % (mcp/metadata-redact % {:secrets secrets}))
                      [(:name p) model])
      (ai/fail "AI defaults cannot contain configured credentials."))
    (when (and free? (not (some #{model} (zen/free-models resolved))))
      (ai/fail "Zen runtime defaults must be freshly verified free models."))
    (check!)
    (when-not (= (dissoc p :model)
                 (dissoc (ai/provider (ai/config) provider-name) :model))
      (ai/fail "AI provider configuration changed. Set the default again."))
    (reset! ai/runtime-default {:provider (:name p) :model model})
    (cond-> (view) free? (assoc :verified_free true))))

(defn reset-default! [check!]
  (check!)
  (reset! ai/runtime-default nil)
  ;; Clear the former Zen-only preference too, restoring configuration fully.
  (reset! zen/preferences {})
  (view))
