(ns yetibot.util.ai-execution
  "Host-owned adapter provenance and request-scoped private-output logging."
  (:require [clojure.string :as string]
            [clojure.set :as set]
            [clojure.core.async :as async]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.opencode :as zen]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.interpreter :as interp]
            [yetibot.core.util.public-result :as public]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.core.util.agent-execution :as agent]
            [robert.hooke :as hooke]
            [taoensso.timbre :as log]
            [yetibot.core.handler :as handler]
            [yetibot.core.chat :as chat]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.command :as command]))

(def ^:dynamic *event* nil)
(def ^:dynamic *direct-command* nil)
(def ^:dynamic *direct-input* nil)
(def ^:dynamic *sensitive* nil)
(def ^:dynamic *request* nil)
(def ^:dynamic *command-capture* false)

(defn- exhausted! []
  (throw (ex-info "Inherited AI host-call budget exhausted or closed."
                  {:ai/error true :ai/request-not-sent true
                   :yetibot.models.ai-tool-trace/reason :budget-exceeded})))

(defn tool-key [tool]
  [(cond (:host-stage? tool) "host" (:native? tool) "yetibot"
         :else (get-in tool [:session :server :name])) (:name tool)])

(defn text-only? []
  (boolean (or (:no-tools? *request*) (some-> *request* :text-only deref))))

(defn require-effects! []
  (when (or (:routing-pending? *request*) (:no-host-execution? *request*))
    (throw (ex-info "This inference source has no host effect authority."
                    {:ai/error true :ai/request-not-sent true}))))

(defn remaining []
  (when *request*
    (let [{:keys [limit used reserved]} @(:budget *request*)]
      (max 0 (- limit used (or reserved 0))))))

(defn admissions
  "Host operations and transfers, distinct from model tokens and tool counts."
  [request]
  (let [{:keys [limit used reserved]} @(:budget request)]
    {:limit limit :admitted used :reserved (or reserved 0)
     :remaining (max 0 (- limit used (or reserved 0)))}))

(defn charge!
  "Compatibility entry charge. Metered families charge at the actual send."
  []
  (when (and *request* (not (:metered? *request*)))
    (let [state (:budget *request*)]
      (locking state
        (when (or (:closed? @state) (not (pos? (remaining)))) (exhausted!))
        (swap! state update :used inc)))))

(defn close-family! [request]
  (locking (:budget request)
    (swap! (:budget request) assoc :closed? true)))

(defn family
  "Create a host-owned finite source family. Dataset lifetime is separate.
   The outer evaluator closes it; without a scope the owning loop closes it."
  [limit generation-limit]
  (when-not (and (integer? limit) (<= 0 limit 128)
                 (integer? generation-limit) (<= 1 generation-limit 262144))
    (exhausted!))
  (let [request {:family-id (str (java.util.UUID/randomUUID)) :metered? true
                 :usage-context-id (usage/context-id)
                 :budget (atom {:limit limit :used 0 :reserved 0
                                :receipts [] :closed? false})
                 :generation-limit generation-limit :tool-ceiling (atom nil)
                 :core-context (atom nil) :text-only (atom false)
                 :accepted-jobs (atom [])}]
    (when agent/*scope*
      (agent/on-close! (:family-id request) #(close-family! request)))
    request))

(defn admit-operation!
  "Host ordinal, not a model tool ID. Always called before external effects."
  [kind]
  (when (not= :inference kind) (require-effects!))
  (when *request*
    (let [request *request* state (:budget request)]
      (if-let [admit (:admit! request)]
        (admit kind agent/*operation*)
        (if-not (:metered? request) (do (charge!) nil)
          (locking state
            (let [{:keys [closed? unknown? used receipts]} @state
                  parent (:ordinal agent/*operation*)]
              (when (or closed? unknown? (not (pos? (remaining)))
                        (if parent
                          (not= :intent (:state (get receipts (dec parent))))
                          (some #(= :intent (:state %)) receipts)))
                (exhausted!))
              (let [handle {:family-id (:family-id request) :ordinal (inc used)
                            :parent-ordinal parent :kind kind :state :intent}]
                (swap! state #(-> % (update :used inc)
                                  (update :receipts conj handle)))
                handle))))))))

(defn finish-operation! [handle outcome]
  (when handle
    (if-let [finish (:finish! *request*)]
      (finish handle outcome)
      (let [state (:budget *request*) i (dec (:ordinal handle))]
        (locking state
          (when-not (and (not (:closed? @state))
                         (= (:family-id *request*) (:family-id handle))
                         (= :intent (get-in @state [:receipts i :state])))
            (exhausted!))
          (when (and (= :definitive (:state outcome))
                     (some #(and (= (:ordinal handle) (:parent-ordinal %))
                                 (not= :definitive (:state %))) (:receipts @state)))
            (exhausted!))
          (swap! state #(cond-> (assoc-in % [:receipts i :state] (:state outcome))
                         (= :unknown (:state outcome)) (assoc :unknown? true))))))))

(defn save-loop!
  "Only the owning outer loop saves continuation. Inner receipts never do."
  ([state] (save-loop! state nil))
  ([state handle]
   (if (or agent/*operation* (:parent-ordinal handle))
     (when handle (finish-operation! handle {:state :definitive}))
     (if-let [save (:checkpoint-loop! *request*)]
       (save state handle)
       (when handle (finish-operation! handle {:state :definitive}))))))

(defn with-request* [request f]
  (let [context (when-let [factory (agent/host-callback :request-context)]
                  (if-let [slot (:core-context request)]
                    (or @slot (locking slot
                                (or @slot (reset! slot (factory request)))))
                    (factory request)))]
  (binding [*request* request
            agent/*context* (or context agent/*context*)
            usage/*context-id* (or (:usage-context-id request) usage/*context-id*)
            usage/*admit-inference*
            (when (:metered? request)
              (fn [attrs]
                (when-let [authorize (:authorize-inference! request)] (authorize attrs))
                (admit-operation! :inference)))
            usage/*finish-inference*
            (when (:metered? request) finish-operation!)]
    (if-let [scheduled (and (:scheduled? request)
                            (some-> (find-ns 'yetibot.core.commands.cron)
                                    (ns-resolve '*scheduled?*)))]
      (with-bindings {scheduled true} (f))
      (f)))))

(defn with-source*
  "Issue a finite family before a source constructs its first request body.
   The installed host owns selection/policy; model options cannot issue one."
  [prepared f]
  (if-let [source (agent/host-callback :source)]
    (source prepared f)
    (f)))

(defn with-routing-source*
  "Only the installed host may preauthorize and freeze the direct router family."
  [prepared args f]
  (if-let [route (agent/host-callback :route-source)]
    (route prepared args f)
    (throw (ex-info "Intent routing authority is unavailable." {:ai/error true}))))

(defn require-live!
  "An already admitted expression may use its last unit; it cannot admit more."
  []
  (when *request*
    (let [{:keys [closed? unknown?]} @(:budget *request*)]
      (when (or closed? unknown?) (exhausted!)))
    (when-let [live! (:require-live! *request*)] (live!))))

(defn require-open! []
  (when *request*
    (require-live!)
    (let [state @(:budget *request*)]
      (when (or (:closed? state) (:unknown? state) (not (pos? (remaining))))
        (exhausted!)))))

(defn clamp-budgets [budgets]
  (if-not *request* budgets
    (-> budgets
        (update :max-output-tokens min (:generation-limit *request*))
        (update :max-tool-calls min (:limit @(:budget *request*))))))

(defn inherited-toolset [requested]
  (if-not *request* requested
    (let [original (:toolset-name *request*)]
      (when (and requested (not= requested original)
                 (not (when-let [narrow? (:narrow-toolset? *request*)]
                        (narrow? requested))))
        (throw (ex-info "Nested AI cannot widen its inherited toolset."
                        {:ai/error true :ai/request-not-sent true})))
      (or requested original))))

(defn intersect-policy
  "Absent constraints are unrestricted, never an excuse to discard a ceiling."
  [original current]
  (let [intersect-values (fn [a b]
                           (cond (nil? a) b (nil? b) a
                                 :else (filterv (set b) a)))
        policy (reduce (fn [out k]
                         (if (or (contains? original k) (contains? current k))
                           (assoc out k (intersect-values (get original k) (get current k))) out))
                       current [:users :adapters :repositories :native-tools])
        providers (fn [p] (set (for [entry p model (:models entry)] [(:name entry) model])))
        a (:providers original) b (:providers current)]
    (cond-> policy
      (or a b)
      (assoc :providers
             (if (= a b) a
              (if (and a b)
               (mapv (fn [[name pairs]] {:name name :models (mapv second pairs)})
                     (sort-by key (group-by first (set/intersection (providers a) (providers b)))))
               (or a b)))))))

(defn sensitive! []
  (when-not *sensitive*
    (throw (ex-info "Private AI output requires a trusted chat request."
                    {:ai/error true})))
  (reset! *sensitive* true))

(defmulti run-followup
  "Dispatch a host-owned route with the human body as literal prompt data."
  (fn [route _args] (:command route)))

(def ^:private expired-message
  (str "This AI conversation changed or expired. "
       "Send a new explicit AI command."))

(defmethod run-followup :default [_ _]
  {:result/error expired-message})

(defn provider-signature [provider]
  (let [identity (select-keys provider [:name :url :api :native-tools :max-turns :gateway-id :continuation-transport])
        digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str identity) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 255 %)) digest))))

(defn ensure-followup! [route provider]
  (when-not (and (= route (discord/command-followup))
                 (= (:provider-signature route) (provider-signature provider)))
    (throw (ex-info expired-message {:ai/error true}))))

(defn revalidate-free-followup! [route provider]
  (if-not (:verified-free route) provider
    (do
      (when-not (and (= "zen" (:name provider)) (zen/provider? provider)
                     (some #{(:model route)} (zen/free-models provider)))
        (throw (ex-info
                 (str "This conversation's model is no longer verified free. "
                      "No inference was sent. Choose a model explicitly.")
                 {:ai/error true})))
      (assoc provider :ai/verified-free true))))

(defn remember-answer!
  "Only a successful standalone original AI command (or owned continuation)
   can open another turn. Never retain a prompt, credentials or pipeline text."
  [command {:keys [provider model caption? pipeline-input actor] :as prepared}
   result]
  (when (and (not *command-capture*) actor caption? (not pipeline-input)
             (string? (:result/value result))
             (not (string/blank? (:result/value result)))
             (not (:result/error result))
             (not (get-in result [:result/data :isError]))
             (or (= command (:command (discord/command-followup)))
                 (and *direct-input*
                      (string/starts-with? (:evaluated *direct-input*)
                                           (str command " ")))))
    (let [route (cond->
                  (merge {:command command :provider (:name provider)
                          :model model
                          :provider-signature (provider-signature provider)}
                         (into {} (filter (comp some? val)
                                     (select-keys prepared
                                       [:no-tools :toolset :max-tool-calls
                                        :max-output-tokens]))))
                  (:ai/verified-free provider) (assoc :verified-free true))
          secrets (keep identity [(:key provider) (:key-file provider)])]
      (when (every? #(= % (mcp/metadata-redact % {:secrets secrets}))
                    [(:name provider) model])
        (try (discord/remember-command-continuation! route)
             ;; Unsupported IDs/options disable continuation, not the answer.
             (catch IllegalArgumentException _ nil)))))
  result)

(defn- dispatch-followup! [route chat-source user yetibot-user body]
  (async/thread
    (binding [interp/*chat-source* chat-source interp/*current-user* user
              interp/*yetibot-user* yetibot-user
              usage/*context-id* (usage/context-id)]
      (let [args {:chat-source chat-source :user user :yetibot-user yetibot-user
                  :cmd (:command route) :args body :match body
                  :next-cmds []}
            result (try (run-followup route args)
                        (catch Exception _
                          {:result/error
                           (str "The AI follow-up outcome could not be confirmed; "
                                "do not retry automatically.")}))]
        (public/observe! result)
        (handler/dispatch-command-response
          {:result (if (chat/suppressed? result) result
                     (or (:result/error result) (:result/value result) result))
           :error? (boolean (:result/error result))})))))

(defn with-adapter-event
  [callback chat-source user event-type yetibot-user event-info]
  (let [body (:body event-info)
        ;; Report arguments must be private before interpreter diagnostics run.
        sensitive (atom (boolean
                          (and (string? body)
                               (re-find #"(?is)(?:^|[^A-Za-z0-9_])ai\s+usage(?:\s|$)"
                                        body))))
        ;; Filter before existing middleware/appenders can inspect source. Keep
        ;; only closed provider diagnostics and sanitized proposal audit events.
        privacy (fn [data]
                  (when (or (not @sensitive)
                            (contains? #{"yetibot.models.ai-proposals"
                                         "yetibot.api.ai-diagnostics"}
                                       (:?ns-str data)))
                    data))]
    (binding [*event* {:chat-source chat-source :user user
                      :event-type event-type :body (:body event-info)
                      :yetibot-user yetibot-user}
              *direct-command* nil
              *direct-input* nil
              *sensitive* sensitive
              log/*config* (update log/*config* :middleware
                                  #(into [privacy] %))]
      (when-let [observe (agent/host-callback :observe-event)]
        (observe *event*))
      (if-let [route (discord/command-followup)]
        (do (sensitive!)
            (dispatch-followup! route chat-source user yetibot-user
                                (:body event-info)))
        (callback chat-source user event-type yetibot-user event-info)))))

(defn- direct-expression [chat-source user tree]
  (let [platform-body (case (:adapter chat-source)
                        :slack (get-in chat-source [:raw-event :text])
                        :discord (get-in chat-source [:raw-event :content])
                        nil)
        body (:body *event*)
        extract #(when (string? %) (second (command/extract-command %)))
        text (extract body)]
    (when (and (= :message (:event-type *event*))
               (= chat-source (:chat-source *event*)) (= user (:user *event*))
               text (= text (extract platform-body))
               (re-matches #"ai\s+(?:(?:proposal|approve|cancel)\s+[A-Za-z0-9-]+(?:\s+[0-9]+)?|default(?:\s+\S+){0,2}|limits(?:\s+reset|(?:\s+--(?:max-output-tokens|max-tool-calls)\s+[1-9][0-9]*){1,2})?)\s*" text)
               (= tree (parser/parser text)))
      (string/join " " (string/split (string/trim text) #"\s+")))))

(defn- direct-input [chat-source user tree]
  (let [platform-body (case (:adapter chat-source)
                        :slack (get-in chat-source [:raw-event :text])
                        :discord (get-in chat-source [:raw-event :content]) nil)
        extract #(when (string? %) (second (command/extract-command %)))
        text (extract (:body *event*))]
    (when (and (= :message (:event-type *event*))
               (= chat-source (:chat-source *event*)) (= user (:user *event*))
               text (= text (extract platform-body)) (= tree (parser/parser text))
               (vector? tree) (= :expr (first tree)) (= 2 (count tree))
               (= :cmd (first (second tree)))
               (not-any? #(and (vector? %) (= :sub-expr (first %))) (tree-seq coll? seq tree)))
      {:raw text :evaluated (parser/transformer (second tree))})))

(defn direct-request-key
  "Only the original standalone !ai prompt can start automatic writes.
   Event IDs, not generated text, give duplicate deliveries a stable identity."
  [{:keys [match]}]
  (let [canonical #(some-> % string/trim (string/replace-first #"^ai\s+" "ai "))
        {:keys [chat-source user]} *event*
        {:keys [adapter uuid raw-event]} chat-source
        id (case adapter :slack (:ts raw-event) :discord (:id raw-event) nil)
        channel (case adapter :slack (:channel raw-event) :discord (:channel-id raw-event) nil)]
    (when (and (string? match) (string? id) (seq id) (string? channel)
               *direct-input* (= (canonical (:evaluated *direct-input*)) (str "ai " (string/trim match))))
      (let [bytes (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes (pr-str [adapter uuid (:id user) channel id]) "UTF-8"))]
        (apply str (map #(format "%02x" (bit-and 255 %)) bytes))))))

(defn direct-request-text [args]
  (when (direct-request-key args)
    (string/replace-first (string/trim (:raw *direct-input*)) #"^ai\s+" "")))

(defn with-direct-expression [callback chat-source user yetibot-user tree]
  (binding [*direct-command* (when-not *command-capture* (direct-expression chat-source user tree))
            *direct-input* (when-not *command-capture* (direct-input chat-source user tree))]
    (callback chat-source user yetibot-user tree)))

(defn require-direct! [args]
  (when-not (and (not *command-capture*) *direct-command*
                 (= *direct-command*
                    (str "ai " (string/join " "
                                (string/split (string/trim (:args args "")) #"\s+")))))
    (throw (ex-info "Send this AI control command directly in chat, without pipes, aliases, observers or computed commands."
                    {:ai/error true}))))

;; The HTTP /api route enters handle-unparsed-expr instead: EDN chat-source
;; metadata cannot establish adapter provenance. go/future convey these bindings.
(hooke/add-hook #'handler/handle-raw #'with-adapter-event)
(hooke/add-hook #'handler/handle-parsed-expr #'with-direct-expression)
