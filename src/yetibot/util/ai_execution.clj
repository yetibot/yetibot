(ns yetibot.util.ai-execution
  "Host-owned adapter provenance and request-scoped private-output logging."
  (:require [clojure.string :as string]
            [clojure.core.async :as async]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.opencode :as zen]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.core.interpreter :as interp]
            [yetibot.core.util.public-result :as public]
            [yetibot.core.util.ai-usage :as usage]
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
  (let [identity (select-keys provider [:name :url :api :native-tools])
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
  (when (and actor caption? (not pipeline-input)
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
                      :event-type event-type :body (:body event-info)}
              *direct-command* nil
              *direct-input* nil
              *sensitive* sensitive
              log/*config* (update log/*config* :middleware
                                  #(into [privacy] %))]
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
  (binding [*direct-command* (direct-expression chat-source user tree)
            *direct-input* (direct-input chat-source user tree)]
    (callback chat-source user yetibot-user tree)))

(defn require-direct! [args]
  (when-not (and *direct-command*
                 (= *direct-command*
                    (str "ai " (string/join " "
                                (string/split (string/trim (:args args "")) #"\s+")))))
    (throw (ex-info "Send this AI control command directly in chat, without pipes, aliases, observers or computed commands."
                    {:ai/error true}))))

;; The HTTP /api route enters handle-unparsed-expr instead: EDN chat-source
;; metadata cannot establish adapter provenance. go/future convey these bindings.
(hooke/add-hook #'handler/handle-raw #'with-adapter-event)
(hooke/add-hook #'handler/handle-parsed-expr #'with-direct-expression)
