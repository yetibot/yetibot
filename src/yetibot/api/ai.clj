(ns yetibot.api.ai
  (:require [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
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
(s/def ::tool-calling #(or (boolean? %) (contains? #{"true" "false"} %)))
(s/def ::tool-choice #{"standard" "omit"})
(s/def ::provider
  (s/keys :req-un [::name ::url]
          :opt-un [::key ::model ::tool-calling ::tool-choice]))

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
    (let [response (method
                    (str (string/replace (:url provider) #"/+$" "") path)
                    (merge {:as :json
                            :throw-exceptions false
                            :conn-timeout 5000
                            :socket-timeout 120000
                            :redirect-strategy :none
                            :retry-handler (fn [& _] false)
                            :headers (cond-> {"Accept" "application/json"}
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
  (let [body (request client/get provider "/models" {})
        data (:data body)]
    (when-not (and (sequential? data) (every? #(nonblank-string? (:id %)) data))
      (fail (str "AI provider " (:name provider) " returned an invalid model list.")))
    (vec (sort (distinct (map :id data))))))

(defn chat
  "Return a chat response, optionally offering OpenAI-compatible function tools."
  [provider model messages options]
  (request client/post provider "/chat/completions"
           {:content-type :json
            :form-params (merge {:model model :messages messages}
                                (cond-> (select-keys options [:tools :tool_choice])
                                  (= "omit" (:tool-choice provider))
                                  (dissoc :tool_choice)))}))

(defn complete [provider model messages]
  (let [body (chat provider model messages {})
        text (get-in body [:choices 0 :message :content])]
    (when-not (nonblank-string? text)
      (fail (str "AI provider " (:name provider) " returned no text response.")))
    {:result/value text
     :result/data body}))
