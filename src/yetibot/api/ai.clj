(ns yetibot.api.ai
  (:require [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [yetibot.api.cloudflare :as cloudflare]
            [yetibot.core.config :refer [get-config]]
            [yetibot.util.ai-config :as settings])
  (:import [java.net URI]))

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
            :opt-un [::key ::model ::tool-calling ::tool-choice ::api
                     ::native-tools ::max-turns ::gateway-id])
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
              "AI configuration is invalid. Check provider names, URLs and defaults.")))
    (settings/ai value)))

(defn provider [config provider-name]
  (let [provider-name (or provider-name (:default-provider config)
                          (:name (first (:providers config))))]
    (or (some #(when (= provider-name (:name %)) %) (:providers config))
        (fail "Unknown AI provider. Use ai providers to list available providers."))))

(defn- request [method provider path params]
  (try
    (let [gateway (when (cloudflare/provider-account provider)
                    (or (:gateway-id provider)
                        (when (string/starts-with?
                                (str (get-in params [:form-params :model])) "@")
                          "default")))
          response (method
                    (str (string/replace (:url provider) #"/+$" "") path)
                    (merge {:as :json
                            :throw-exceptions false
                            :conn-timeout 5000
                            :socket-timeout 120000
                            :redirect-strategy :none
                            :retry-handler (fn [& _] false)
                            :headers (cond->
                                       (merge {"Accept" "application/json"}
                                              (cloudflare/gateway-headers
                                                gateway))
                                       (:key provider)
                                       (assoc "Authorization" (str "Bearer " (:key provider))))}
                           params))]
      (when-not (<= 200 (:status response) 299)
        (fail (str "AI provider " (:name provider) " returned HTTP " (:status response) ".")))
      (:body response))
    (catch Exception e
      (if (:ai/error (ex-data e))
        (throw e)
        ;; Neither exception messages nor response bodies are safe to echo.
        (fail (str "Could not reach AI provider " (:name provider) " or read its response."))))))

(defn models [provider]
  (if (cloudflare/provider-account provider)
    (let [catalog (cloudflare/catalog provider)]
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
  (let [responses-api? (responses? provider)
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
              (merge params
                     (cond-> (select-keys options [:tools :tool_choice])
                       (= "omit" (:tool-choice provider))
                       (dissoc :tool_choice)))})))

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

(defn complete [provider model messages]
  (let [body (chat provider model messages {})
        text (:content (message provider body))]
    (when-not (nonblank-string? text)
      (fail (str "AI provider " (:name provider) " returned no "
                 (when (responses? provider) "completed ")
                 "text response.")))
    {:result/value text
     :result/data body}))
