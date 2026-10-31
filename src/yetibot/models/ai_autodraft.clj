(ns yetibot.models.ai-autodraft
  "Opt-in intent routing; models never choose credentials or authorize writes."
  (:require [yetibot.api.ai :as ai]
            [yetibot.api.clef :as clef]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-github :as github]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.models.ai-tools :as tools]
            [yetibot.util.ai-execution :as execution]))

(defonce ^:private requests (atom {}))
(def receipt-ttl-ms 86400000)
(defn- reserve! [key]
  (locking requests
    (swap! requests #(into {} (filter (fn [[_ r]]
                                       (or (= :running (:state r))
                                           (> (:expires-at r) (System/currentTimeMillis)))) %)))
    (if-let [receipt (get @requests key)]
      {:duplicate receipt}
      (do (let [read-only (filter #(= :read-only (:state (val %))) @requests)]
            (when (>= (count read-only) 128)
              (swap! requests dissoc (ffirst (sort-by #(get-in % [1 :expires-at]) read-only)))))
          (when (>= (count (remove #(= :read-only (:state (val %))) @requests)) 128)
            (ai/fail "Too many automatic requests. Try again later."))
          (swap! requests assoc key {:state :running :expires-at (+ (System/currentTimeMillis) receipt-ttl-ms)})
          {:reserved true}))))

(defn- eligible? [t actor provider model]
  (and (= "github-pr" (:kind t)) actor
       (try (policy/authorize! t actor provider model) true (catch Exception _ false))))

(defn- repository [t text]
  (let [matches (filter #(re-find (re-pattern (str "(?i)(?<![A-Za-z0-9_.-])"
                                                  (java.util.regex.Pattern/quote %)
                                                  "(?![A-Za-z0-9_.-])")) text)
                        (:repositories t))]
    (when (= 1 (count matches)) (first matches))))

(defn- execute-change!
  [{:keys [provider model messages actor max-tool-calls on-progress]}
   args key router config t repo]
  (let [secret (clef/credential! router)
        private-config (update config :secrets #(concat % [secret (:key provider)]))
        messages (mapv #(update % :content (fn [text] (if (string? text) (mcp/redact text private-config) text))) messages)
        generated (tools/complete provider model
                                  (into [{:role "system"
                                          :content (str "The current direct request asks for a code change in " repo
                                                        ". Read the relevant files and stage_changes alone. "
                                                        "The host will open a draft PR automatically; do not claim tests were run.")}]
                                        messages)
                                  (cond-> {:toolset (:name t) :actor actor
                                           :additional-secrets [secret]}
                                    (some? max-tool-calls)
                                    (assoc :max-tool-calls max-tool-calls)
                                    on-progress
                                    (assoc :on-progress on-progress)))
        id (get-in generated [:result/data :proposal])]
    (when-not id (ai/fail "The model did not stage a change. No GitHub writes were made."))
    (let [p (proposals/review! id actor)
          current-config (mcp/config)
          current-t (:toolset (toolsets/select current-config (:name t)))
          current-provider (ai/provider (ai/config) (:name provider))]
      (when-not (and (= repo (str (:owner p) "/" (:repo p)))
                     (= t current-t (:toolset p))
                     (= (:provider p) (select-keys provider [:name :url])) (= model (:model p))
                     (= (select-keys provider [:name :url]) (select-keys current-provider [:name :url]))
                     (ai/tool-calling? current-provider)
                     (= router (:intent-router (ai/config))))
        (proposals/cancel! id actor)
        (ai/fail "The generated repository or execution policy changed. No GitHub writes were made."))
      (policy/authorize! current-t actor current-provider model)
      (let [claimed (proposals/claim-automatic! id actor key args)
            result (github/execute! current-config claimed)]
        (proposals/finish! id result)
        {:result/data result
         :result/value (if (= :succeeded (:status result))
                         (str "Draft PR: " (:pr-url result))
                         (str "Automatic PR stopped: " (name (:status result))
                              (when-let [branch (:branch result)] (str "\nBranch: " branch))
                              (when-let [commit (:commit result)] (str "\nCommit: " commit))
                              "\nInspect any known branch/commit before sending a new request; no automatic retry."))}))))

(defn complete
  "Route plain direct !ai prompts only. Explicit toolsets/no-tools and other
   entrypoints keep their existing behavior. A decision never grants access."
  [{:keys [provider model actor prompt no-tools toolset] :as prepared} args fallback]
  (let [router (:intent-router (ai/config))
        key (when (and (clef/enabled? router) (not no-tools) (nil? toolset) actor)
              (execution/direct-request-key args))]
    (if-not key
      (fallback prepared)
      (let [config (mcp/config)
            t (:toolset (toolsets/select config (:github-toolset router)))]
        (if-not (eligible? t actor provider model)
          (fallback prepared)
          (do
            (execution/sensitive!)
            (let [{:keys [duplicate]} (reserve! key)]
              (if duplicate
                (or (:result duplicate) "This request is already running; it will not be executed twice.")
                (let [code-started (atom false)]
                  (try
                    (let [request-text (execution/direct-request-text args)
                          cleaned (mcp/redact request-text (update config :secrets (fnil conj []) (:key provider)))
                          _ (when-not (= cleaned request-text)
                              (ai/fail "The request contains configured credentials; no classification or writes were performed."))
                          decision (clef/classify! router request-text)
                          result (case decision
                                   :answer (fallback prepared)
                                   :clarify "Do you want repository changes, or just an answer? For changes, name owner/repo and the requested edit."
                                   :code-change (if-let [repo (repository t prompt)]
                                                  (do (reset! code-started true)
                                                      (execute-change! prepared args key router config t repo))
                                                  "Which repository should I change? Include owner/repo in a new !ai request.")
                                   (ai/fail "Invalid intent decision. No automatic changes were made."))]
                      (if @code-started
                        (swap! requests update key assoc :state :done :result result)
                        (swap! requests update key assoc :state :read-only
                               :result "This request was already handled without GitHub writes. Send a new !ai message for a new change request."))
                      result)
                    (catch Exception e
                      ;; Only code work can leave a write outcome needing a receipt.
                      (if @code-started
                        (swap! requests update key assoc :state :done
                               :result "This request already stopped; inspect any known outcome before sending a new request.")
                        (swap! requests update key assoc :state :read-only
                               :result "This request already stopped without automatic GitHub writes. Send a new message to try again."))
                      (throw e))))))))))))
