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
  ;; This separate-JVM program has no authenticated host ingress. A spend
  ;; ledger, SDK token, ambient event or supplied Context is not a CLI grant.
  (if (not= "greeting" case-name)
    (let [report {:case (str provider-name ":" case-name) :passed false
                  :known-not-sent true :unsupported-live-callback true
                  :real-tool-callbacks 0 :inference-posts 0}]
      (println "LIVE_AI_SMOKE" (pr-str report))
      report)
    (let [original-post http/post original-chat ai/chat
          wires (atom []) response-shapes (atom []) allowed-url (atom nil)
          label (str provider-name ":" case-name)
          guarded-post
          (fn [url options]
            (let [body (:form-params options)
                  cap (or (:max_output_tokens body) (:max_tokens body))]
              (when-not (and (= url @allowed-url) (integer? cap) (<= 1 cap 2048)
                             (empty? (:tools body)))
                (throw (ex-info "Unapproved live smoke request." {:ai/error true})))
              (ledger!
                (fn [state]
                  (when (or (:stopped state) (:pending state) (>= (:used state) (:cap state)))
                    (throw (ex-info "Live smoke budget stopped." {:ai/error true})))
                  (-> state (update :used inc) (assoc :pending true)
                      (update :attempts conj {:case label}))))
              (swap! wires conj {:tools 0})
              (let [response (original-post url options)
                    choice (when (map? (:body response)) (first (:choices (:body response))))
                    message (:message choice)]
                (when choice
                  (swap! response-shapes conj
                         {:finish (get {"length" :length "stop" :stop "tool_calls" :tool-calls}
                                       (:finish_reason choice) :unrecognized)
                          :visible-text? (ai/nonblank-string? (:content message))
                          :reasoning? (ai/nonblank-string? (:reasoning_content message))}))
                response))) ]
      (ledger! identity)
      (binding [log/*config* (assoc log/*config* :middleware [(constantly nil)])
                execution/*sensitive* (atom true)]
        (with-redefs [http/post guarded-post
                      ai/chat
                      (fn [p model messages options]
                        (try
                          (let [result (original-chat p model messages
                                                      (assoc options :max-output-tokens 2048))]
                            (ledger! #(assoc % :pending false))
                            result)
                          (catch Throwable e
                            (let [record (:ai/diagnostic (ex-data e))
                                  rejected? (and (= :http (:failure record))
                                                 (contains? #{400 401 402 403 404 405 413 415 422 429}
                                                            (:status record)))]
                              (when (:pending (ledger! identity))
                                (ledger! #(if (or rejected?
                                                  (contains? #{:output-limit :no-answer :unsupported-output :incomplete-output}
                                                             (:ai/output-error (ex-data e))))
                                            (assoc % :pending false) (assoc % :stopped true)))))
                            (throw e))))]
          (try
            (let [provider (selected-provider provider-name)
                  _ (reset! allowed-url (str (string/replace (:url provider) #"/+$" "")
                                            (if (ai/responses? provider) "/responses" "/chat/completions")))
                  result (ai/complete provider (:model provider)
                                      (tools/host-messages [{:role "user" :content "yo"}]))
                  report {:case label :passed (boolean (ai/nonblank-string? (:result/value result)))
                          :real-tool-callbacks 0 :inference-posts (count @wires)
                          :response-shapes @response-shapes
                          :requests-used (:used (ledger! identity))}]
              (println "LIVE_AI_SMOKE" (pr-str report)) report)
            (catch Throwable e
              (let [record (:ai/diagnostic (ex-data e))
                    uncertain? (or (:pending (ledger! identity)) (:stopped (ledger! identity))
                                   (= :transport (:failure record))
                                   (and (integer? (:status record)) (>= (:status record) 500)))]
                (when uncertain? (ledger! #(assoc % :stopped true)))
                (let [report {:case label :passed false :real-tool-callbacks 0
                              :inference-posts (count @wires) :response-shapes @response-shapes
                              :requests-used (:used (ledger! identity))
                              :uncertain-outcome (boolean uncertain?)
                              :failure (select-keys record [:stage :endpoint :provider :failure :status
                                                           :error-class :error-code :parameter :tool-count :tool-choice])}]
                  (println "LIVE_AI_SMOKE" (pr-str report)) report)))))))))

(when (seq *command-line-args*)
  (let [result (apply run! *command-line-args*)]
    (shutdown-agents)
    (System/exit (if (:passed result) 0 1))))
