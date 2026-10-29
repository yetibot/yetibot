;; Opt-in operator program for a SEPARATE JVM, never the live service REPL.
;; No budget initialization/reset, scheduler, model-selected endpoint or shell.
(when-not (= "true" (System/getProperty "yetibot.live-smoke.enabled"))
  (throw (ex-info "Live smoke requires explicit operator opt-in." {})))

(require 'taoensso.timbre)
(alter-var-root #'taoensso.timbre/*config* assoc
                :middleware [(constantly nil)])

(ns yetibot.scripts.ai-live-smoke
  (:refer-clojure :exclude [run!])
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [taoensso.timbre :as log]
            [yetibot.api.ai :as ai]
            [yetibot.api.cloudflare :as cf]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-alias-tools :as aliases]
            [yetibot.models.ai-native-tools :as native]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.core.util.xai :as xai]
            [yetibot.util.ai-execution :as execution])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.file Paths StandardOpenOption OpenOption]))

(defn- ledger! [f]
  (let [path (System/getProperty "yetibot.live-smoke.budget")]
    (when-not (and path (.isAbsolute (java.io.File. path)))
      (throw (ex-info "An existing absolute budget file is required." {})))
    (with-open [channel
                (FileChannel/open
                  (Paths/get path (make-array String 0))
                  (into-array OpenOption [StandardOpenOption/READ
                                          StandardOpenOption/WRITE]))
                lock (.lock channel)]
      (when-not (< 0 (.size channel) 16384)
        (throw (ex-info "Invalid live smoke ledger size." {})))
      (let [buffer (ByteBuffer/allocate (int (.size channel)))]
        (while (.hasRemaining buffer) (.read channel buffer))
        (let [state (try
                      (json/parse-string
                        (String. (.array buffer) "UTF-8") true)
                      (catch Exception _
                        (throw (ex-info "Invalid smoke ledger JSON." {}))))]
          (when-not (and (integer? (:cap state)) (<= 1 (:cap state) 18)
                         (integer? (:used state))
                         (<= 0 (:used state) (:cap state))
                         (vector? (:attempts state)))
            (throw (ex-info "Invalid live smoke ledger." {})))
          (let [next (f state)]
            (when (not= state next)
              (.position channel 0)
              (.truncate channel 0)
              (let [bytes (ByteBuffer/wrap
                            (.getBytes (json/generate-string next) "UTF-8"))]
                (while (.hasRemaining bytes) (.write channel bytes)))
              (.force channel true))
            next))))))

(defn- selected-provider [name]
  (let [configured (some #(when (= name (:name %)) %)
                         (:providers (ai/config)))
        provider (or configured
                     (when (= name "grok")
                       {:name "grok" :url "https://api.x.ai/v1"
                        :key (:key xai/config) :model "grok-4.7"
                        :api "responses"}))]
    (when-not (and (contains? #{"grok" "cloudflare"} name)
                   provider (ai/nonblank-string? (:model provider)))
      (throw (ex-info "Configured smoke provider/model unavailable." {})))
    (dissoc provider :native-tools)))

(def event
  {:event-type :message :user {:id "operator-smoke"}
   :chat-source {:adapter :discord :uuid :memeteam :room "operator-smoke"
                 :raw-event {:author {:id "operator-smoke"}
                             :channel-id "operator-smoke"
                             :id "operator-smoke"}}})

(def cases #{"greeting" "sum" "budget" "aliases" "unknown" "random-model"})

(defn- command-check [text model]
  ;; Parse examples using the actual command parser, not line-layout guesses.
  ;; Nothing is dispatched and no provider/configuration is resolved here.
  (require 'yetibot.commands.ai)
  (let [parse (ns-resolve 'yetibot.commands.ai 'parse-prompt)
        snippets (concat
                   (map second (re-seq #"(?s)```[^\n]*\n(.*?)```" text))
                   (map second (re-seq #"`([^`\r\n]+)`" text))
                   (string/split-lines text))
        parsed (keep (fn [snippet]
                       (let [index (.indexOf ^String snippet "!ai ")]
                         (when (<= 0 index)
                           (try (parse (subs snippet (+ index 4)))
                                (catch Exception _ nil))))) snippets)
        valid? (boolean
                 (some #(and (= "cloudflare" (:provider %))
                             (= model (:model %))
                             (ai/nonblank-string? (:prompt %))) parsed))]
    {:ai-example-present (string/includes? text "!ai ")
     :provider-parsed (boolean (some #(= "cloudflare" (:provider %)) parsed))
     :model-parsed (boolean (some #(= model (:model %)) parsed))
     :valid-command valid?
     :shell-command (boolean
                      (re-find #"(?m)^\s*(?:\$\s*)?(?:bash|shuf|sed|for)\b"
                               text))}))

(defn run! [provider-name case-name]
  (when-not (= "true" (System/getProperty "yetibot.live-smoke.enabled"))
    (throw (ex-info "Live smoke requires explicit operator opt-in." {})))
  (when-not (and (contains? #{"grok" "cloudflare"} provider-name)
                 (contains? cases case-name))
    (throw (ex-info "Unknown live smoke case." {})))
  (ledger! identity)
  (let [original-post http/post
        original-chat ai/chat
        callbacks (atom [])
        selected-model (atom nil)
        wires (atom [])
        response-shapes (atom [])
        allowed-url (atom nil)
        label (str provider-name ":" case-name)
        reserve! (fn []
                   (ledger!
                     (fn [state]
                       (when (or (:stopped state) (:pending state)
                                 (>= (:used state) (:cap state)))
                         (throw (ex-info "Live smoke budget stopped."
                                         {:ai/error true})))
                       (-> state (update :used inc)
                           (assoc :pending true)
                           (update :attempts conj {:case label})))))
        guarded-post
        (fn [url options]
          (let [body (:form-params options)
                cap (or (:max_output_tokens body) (:max_tokens body))]
            (when-not (and (= url @allowed-url)
                           (integer? cap) (<= 1 cap 2048)
                           (not-any? #(contains? #{"web_search" "x_search"}
                                                 (:type %)) (:tools body)))
              (throw (ex-info "Unapproved live smoke request."
                              {:ai/error true})))
            (reserve!)
            ;; Store shape only, never prompts, headers, ciphertext or results.
            (swap! wires conj
                   {:tools (count (:tools body))
                    :choice (get body :tool_choice :omitted)
                    :stream? (true? (:stream body))})
            (let [response (original-post url options)
                  body (:body response)
                  choice (when (map? body) (first (:choices body)))
                  message (:message choice)]
              (when choice
                (swap! response-shapes conj
                       {:finish (get {"length" :length "stop" :stop
                                      "tool_calls" :tool-calls}
                                     (:finish_reason choice) :unrecognized)
                        :visible-text? (ai/nonblank-string?
                                         (:content message))
                        :reasoning? (ai/nonblank-string?
                                      (:reasoning_content message))}))
              response)))]
    (binding [log/*config* (assoc log/*config* :middleware [(constantly nil)])
              execution/*sensitive* (atom true)
              execution/*event* event]
      (with-redefs-fn
        {#'http/post guarded-post
         #'ai/chat
         (fn [p model messages options]
           (try
             (let [result (original-chat
                            p model messages
                            (assoc options :max-output-tokens 2048))]
               (ledger! #(assoc % :pending false))
               result)
             (catch Throwable e
               (let [record (:ai/diagnostic (ex-data e))
                     rejected? (and (= :http (:failure record))
                                    (contains? #{400 401 402 403 404 405 413
                                                 415 422 429}
                                               (:status record)))]
                 (when (:pending (ledger! identity))
                   (ledger! #(if rejected? (assoc % :pending false)
                                 (assoc % :stopped true)))))
               (throw e))))
         ;; Never read real aliases. Exercise the actual native adapter around
         ;; this harmless store fixture, without creating/wiring an alias.
         #'aliases/query!
         (fn [_] [{:cmd-name "smoke" :cmd "echo smoke-ok"}])
         #'mcp/call-tool
         (fn [& _] (throw (ex-info "External tools prohibited in smoke." {})))}
        (fn []
          (try
            (let [provider (selected-provider provider-name)
                  model (:model provider)
                  chat-ids
                  (when (= case-name "random-model")
                    (->> (cf/catalog
                           (ai/resolve-provider
                             (selected-provider "cloudflare")))
                         :models (filter #(= :chat (:interface %)))
                         (mapv :id)))
                  _ (when (and chat-ids (not (<= 1 (count chat-ids) 128)))
                      (throw (ex-info "Catalog exceeds smoke bounds." {})))
                  _ (reset! allowed-url
                            (str (string/replace (:url provider) #"/+$" "")
                                 (if (= "responses" (:api provider))
                                   "/responses" "/chat/completions")))
                  operation (if (= case-name "aliases") "aliases" "util")
                  config {:max-tool-calls
                          (if (contains? #{"sum" "unknown"} case-name) 2 1)
                          :servers [] :secrets (vec (keep identity
                                                        [(:key provider)]))
                          :on-progress (fn [_])
                          :toolset
                          {:name "operator-live-smoke"
                           :users ["operator-smoke"] :adapters ["discord"]
                           :providers [{:name provider-name :models [model]}]
                           :native-tools [operation]}}
                  native-tools
                  (mapv
                    (fn [tool]
                      (let [call (:host-call tool)]
                        (assoc tool :host-call
                               (fn [args]
                                 (let [result (call args)
                                       value (:structuredContent result)
                                       ok (cond
                                            (= operation "aliases")
                                            (and (:found value)
                                                 (:read_only value)
                                                 (= "echo smoke-ok"
                                                    (:body value)))
                                            (= case-name "random-model")
                                            (and (= "random"
                                                    (get args "operation"))
                                                 (some #{(:result value)}
                                                       chat-ids))
                                            :else (= 0.3M (:result value)))]
                                   (when (= case-name "random-model")
                                     (reset! selected-model (:result value)))
                                   (swap! callbacks conj (boolean ok))
                                   (if (= case-name "unknown")
                                     {:isError true :unknownOutcome true
                                      :structuredContent
                                      {:error "Synthetic uncertain outcome."}}
                                     result))))))
                    (native/tools config (policy/actor event) provider model))
                  prompt (case case-name
                           "greeting" "yo"
                           "random-model"
                           (str "Using the ai command how would I pick a "
                                "random model from our massive list? "
                                "The supplied JSON array contains the "
                                "Cloudflare catalog chat IDs. Call util "
                                "random exactly once on this array, then "
                                "show a Yetibot ai command with the chosen "
                                "ID and explicit cloudflare provider. Do "
                                "not invoke that model or search. Catalog: "
                                (json/generate-string chat-ids))
                           "aliases"
                           (str "Read alias smoke with the aliases tool once. "
                                "Explain its stored definition, never run it.")
                           (str "Use util exactly once: sum the numeric JSON "
                                "array [0.1,0.2]. Report its result. If the "
                                "tool fails, explain that and do not retry."))
                  result (if (= case-name "greeting")
                           (ai/complete provider model
                                        (tools/host-messages
                                          [{:role "user" :content prompt}]))
                           (@#'tools/run-tools
                             provider model [{:role "user" :content prompt}]
                             native-tools config))
                  text (:result/value result)
                  format-checks (when (= case-name "random-model")
                                  (command-check text @selected-model))
                  greeting? (= case-name "greeting")
                  terminal? (contains? #{"budget" "aliases" "unknown"
                                         "random-model"} case-name)
                  responses? (= "responses" (:api provider))
                  terminal-ok
                  (or (not terminal?)
                      (if responses?
                        (= {:tools 0 :choice :omitted :stream? true}
                           (last @wires))
                        (= :none (keyword (:choice (last @wires))))))
                  ok (and (ai/nonblank-string? text)
                          (if greeting? (empty? @callbacks)
                            (= [true] @callbacks))
                          terminal-ok
                          (if format-checks
                            (and (:valid-command format-checks)
                                 (not (:shell-command format-checks)))
                            true)
                          (if (contains? #{"sum" "budget"} case-name)
                            (boolean (re-find #"(?<!\d)0\.3(?!\d)" text))
                            true))
                  report {:case label :passed (boolean ok)
                          :real-tool-callbacks (count @callbacks)
                          :callback-results-correct (every? true? @callbacks)
                          :terminal-wire-correct (boolean terminal-ok)
                          :inference-posts (count @wires)
                          :response-shapes @response-shapes
                          :catalog-chat-candidates (count chat-ids)
                          :command-format-checks format-checks
                          :requests-used (:used (ledger! identity))
                          :synthetic-operator-jvm true}]
              (println "LIVE_AI_SMOKE" (pr-str report))
              report)
            (catch Throwable e
              (let [record (:ai/diagnostic (ex-data e))
                    uncertain? (or (:pending (ledger! identity))
                                   (:stopped (ledger! identity))
                                   (= :transport (:failure record))
                                   (and (integer? (:status record))
                                        (>= (:status record) 500)))]
                (when uncertain?
                  (ledger! #(assoc % :stopped true)))
                (let [report
                      {:case label :passed false
                       :real-tool-callbacks (count @callbacks)
                       :inference-posts (count @wires)
                       :response-shapes @response-shapes
                       :requests-used (:used (ledger! identity))
                       :uncertain-outcome (boolean uncertain?)
                       :failure (select-keys record
                                  [:stage :endpoint :provider :failure
                                   :status :error-class :error-code
                                   :parameter :tool-count :tool-choice])}]
                  (println "LIVE_AI_SMOKE" (pr-str report))
                  report)))))))))

(when (seq *command-line-args*)
  (let [result (apply run! *command-line-args*)]
    (shutdown-agents)
    (System/exit (if (:passed result) 0 1))))
