(ns yetibot.api.ai
  (:require [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [yetibot.api.cloudflare :as cloudflare]
            [yetibot.api.ai-stream :as stream]
            [yetibot.core.config :refer [get-config]]
            [yetibot.util.ai-config :as settings])
  (:import [java.io File FileInputStream]
           [java.net URI]))

(defn nonblank-string? [value]
  (and (string? value) (not (string/blank? value))))

(defn base-url? [value]
  (and (nonblank-string? value)
       (try
         (let [uri (URI. value)]
           (and (#{"http" "https"} (.getScheme uri))
                (some? (.getHost uri))
                (not (.getUserInfo uri))
                (not (.getQuery uri))
                (not (.getFragment uri))))
         (catch Exception _ false))))

(s/def ::name (s/and nonblank-string? #(not (re-find #"\s" %))))
(s/def ::url base-url?)
(s/def ::key nonblank-string?)
(s/def ::key-file
  #(and (nonblank-string? %) (.isAbsolute (File. ^String %))))
(s/def ::model nonblank-string?)
(s/def ::gateway-id #(and (string? %)
                          (re-matches #"[a-zA-Z0-9_-]{1,64}" %)))
(s/def ::tool-calling #(or (boolean? %) (contains? #{"true" "false"} %)))
(s/def ::tool-choice #{"standard" "omit"})
(s/def ::api #{"chat-completions" "responses"})
(s/def ::native-tools
  (s/and (s/coll-of #{"web_search" "x_search"} :kind vector?)
         #(or (empty? %) (apply distinct? %))))
(s/def ::max-turns
  #(try
     (and (or (integer? %) (string? %))
          (<= 1 (Long/parseLong (str %)) 20))
     (catch Exception _ false)))
(s/def ::provider
  (s/and
    (s/keys :req-un [::name ::url]
            :opt-un [::key ::key-file ::model ::tool-calling ::tool-choice ::api
                     ::native-tools ::max-turns ::gateway-id])
    #(not (and (contains? % :key) (contains? % :key-file)))
    #(or (= "responses" (:api %))
         (not (or (seq (:native-tools %)) (contains? % :max-turns))))))

(defn responses? [provider]
  (= "responses" (:api provider)))

(defn tool-calling? [provider]
  (not (contains? #{false "false"} (:tool-calling provider))))
(s/def ::providers
  (s/and (s/coll-of ::provider :kind vector? :min-count 1)
         #(apply distinct? (map :name %))))
(s/def ::default-provider ::name)
(s/def ::settings
  (s/and (s/keys :req-un [::providers] :opt-un [::default-provider])
         #(or (nil? (:default-provider %))
              (some (fn [provider] (= (:default-provider %) (:name provider)))
                    (:providers %)))))

(s/def ::config #(s/valid? ::settings (settings/ai %)))

(defn fail [message]
  (throw (ex-info message {:ai/error true})))

(defn config []
  (let [{:keys [value error]} (get-config ::config [:ai])]
    (when error
      ;; Spec error details can contain API keys. Never return or log them.
      (fail (if (= :not-found error)
              "AI is not configured. Configure ai.providers first."
              (str "AI configuration is invalid. Check provider names, "
                   "URLs and defaults."))))
    (settings/ai value)))

(defn provider [config provider-name]
  (let [provider-name (or provider-name (:default-provider config)
                          (:name (first (:providers config))))]
    (or (some #(when (= provider-name (:name %)) %) (:providers config))
        (fail (str "Unknown AI provider. Use ai providers to list "
                   "available providers.")))))

(defn resolve-provider
  "Resolve only a selected request's configured credential, never discovery.
   References and inline keys are mutually exclusive. Never echo file details."
  [provider]
  (if-not (contains? provider :key-file) provider
    (try
      (when (contains? provider :key) (throw (Exception.)))
      (let [file (File. ^String (:key-file provider))]
        (when-not (and (.isAbsolute file) (.isFile file) (.canRead file))
          (throw (Exception.)))
        (let [token (with-open [input (FileInputStream. file)]
                      (string/trim
                        (String. ^bytes (cloudflare/read-bytes input 8192)
                                 "UTF-8")))]
          (when-not (re-matches #"[\x21-\x7e]{1,8192}" token)
            (throw (Exception.)))
          (-> provider (dissoc :key-file) (assoc :key token))))
      (catch Exception _
        (fail "AI provider credentials are unavailable or invalid.")))))

(defn- request [method provider path params & [progress]]
  (try
    (let [provider (resolve-provider provider)
          gateway (when (cloudflare/provider-account provider)
                    (or (:gateway-id provider)
                        (when (string/starts-with?
                                (str (get-in params [:form-params :model])) "@")
                          "default")))
          response (method
                    (str (string/replace (:url provider) #"/+$" "") path)
                    (merge {:as (if progress :stream :json)
                            :throw-exceptions false
                            :conn-timeout 5000
                            :socket-timeout 120000
                            :redirect-strategy :none
                            :retry-handler (fn [& _] false)
                            :headers (cond->
                                       (merge {"Accept" (if progress
                                                          "text/event-stream"
                                                          "application/json")}
                                              (cloudflare/gateway-headers
                                                gateway))
                                       (:key provider)
                                       (assoc "Authorization"
                                              (str "Bearer "
                                                   (:key provider))))}
                           (when progress {:save-request true}) params))
          read! (fn [body]
                  (when-not (<= 200 (:status response) 299)
                    (fail (str "AI provider " (:name provider)
                               " returned HTTP " (:status response) ".")))
                  (if progress
                    (stream/read-response
                      body (get-in response [:headers "content-type"])
                      (set (map :type (get-in params [:form-params :tools])))
                      (:callback progress) (:config progress))
                    body))]
      (if progress
        (cloudflare/consume-response response read!)
        (read! (:body response))))
    (catch Exception e
      (if (:ai/error (ex-data e))
        (throw e)
        ;; Neither exception messages nor response bodies are safe to echo.
        (fail (str "Could not reach AI provider " (:name provider)
                   " or read its response."))))))

(defn models [provider]
  (if (cloudflare/provider-account provider)
    (let [catalog (cloudflare/catalog (resolve-provider provider))]
      (with-meta (mapv :id (:models catalog)) {:catalog catalog}))
    (let [body (request client/get provider "/models" {})
          data (:data body)]
      (when-not (and (sequential? data)
                     (every? #(nonblank-string? (:id %)) data))
        (fail (str "AI provider " (:name provider)
                   " returned an invalid model list.")))
      (vec (sort (distinct (map :id data)))))))

(defn chat
  "Request a provider turn, keeping encrypted Responses state client-side."
  [provider model messages options]
  (when (and (contains? options :max-output-tokens)
             (not (and (integer? (:max-output-tokens options))
                       (<= 1 (:max-output-tokens options) 2048))))
    (fail "AI output token limit must be an integer from 1 to 2048."))
  (let [provider (resolve-provider provider)
        responses-api? (responses? provider)
        progress (when (and responses-api? (fn? (:on-progress options)))
                   {:callback (:on-progress options)
                    :config (update (:progress-config options {}) :secrets
                                    #(conj (vec %) (:key provider)))})
        options (if responses-api?
                  (assoc options :tools
                         (if (= "none" (:tool_choice options)) []
                           (into (mapv #(hash-map :type %)
                                       (:native-tools provider))
                                 (map #(assoc (:function %) :type "function")
                                      (:tools options)))))
                  options)
        params (if responses-api?
                 {:model model :input messages :store false
                  :include ["reasoning.encrypted_content"]
                  :max_turns (Long/parseLong
                               (str (or (:max-turns provider) 6)))}
                 {:model model :messages messages})]
    (request client/post provider
             (if responses-api? "/responses" "/chat/completions")
             {:content-type :json
              :form-params
              (merge (cond-> params
                       progress (assoc :stream true)
                       (:max-output-tokens options)
                       (assoc (cond
                                responses-api? :max_output_tokens
                                (cloudflare/provider-account provider)
                                :max_tokens
                                :else :max_completion_tokens)
                              (:max-output-tokens options)))
                     (cond-> (select-keys options [:tools :tool_choice])
                       (= "omit" (:tool-choice provider))
                       (dissoc :tool_choice)))}
             progress)))

(defn message
  "Normalize only client-side function calls; native searches run at xAI."
  [provider body]
  (if-not (responses? provider)
    (get-in body [:choices 0 :message])
    (do
      (when-not (and (= "completed" (:status body))
                     (sequential? (:output body)))
        (fail (str "AI provider " (:name provider)
                   " returned no completed text response.")))
      {:role "assistant"
       :content (->> (:output body)
                     (filter #(= "message" (:type %)))
                     (mapcat :content)
                     (filter #(= "output_text" (:type %)))
                     (map :text)
                     (string/join ""))
       :tool_calls
       (mapv (fn [item]
               {:type "function" :id (:call_id item)
                :function (select-keys item [:name :arguments])})
             (filter #(= "function_call" (:type %)) (:output body)))})))

(defn continue-messages
  "Carry all Responses output, including opaque reasoning/search state, forward.
   Tool replies use call_id, which is distinct from a Responses item's id."
  [provider messages body replies]
  (if (responses? provider)
    (into (into messages (:output body))
          (map (fn [reply]
                 {:type "function_call_output" :call_id (:tool_call_id reply)
                  :output (:content reply)}) replies))
    (let [{:keys [content tool_calls]} (message provider body)]
      (into (conj messages {:role "assistant" :content content
                           :tool_calls tool_calls}) replies))))

(defn complete
  ([provider model messages] (complete provider model messages {}))
  ([provider model messages options]
   (let [body (chat provider model messages options)
         text (:content (message provider body))]
     (when-not (nonblank-string? text)
       (fail (str "AI provider " (:name provider) " returned no "
                  (when (responses? provider) "completed ")
                  "text response.")))
     {:result/value text
      :result/data body})))
