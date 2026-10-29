(ns yetibot.api.ai-diagnostics
  "Closed, host-owned failure diagnostics. Never emit provider text."
  (:require [cheshire.core :as json]
            [taoensso.timbre :as log]
            [yetibot.api.cloudflare :as cloudflare])
  (:import [com.fasterxml.jackson.core JsonFactory JsonToken]
           [java.io InputStream]))

(defn- tag [allowed value fallback]
  (if (or (keyword? value)
          (and (string? value) (<= (count value) 128)))
    (get allowed value fallback)
    fallback))

(defn- saturated-count [limit values]
  (if (sequential? values) (count (take limit values)) 0))

(defn context [provider path form stage]
  (let [input (or (:input form) (:messages form))
        items (cond
                (string? input) [{:type "message"}]
                (sequential? input) (take 256 input)
                :else [])
        kinds {"message" :message "reasoning" :reasoning
               "function_call" :function-call
               "function_call_output" :function-call-output
               "web_search_call" :web-search "x_search_call" :x-search}
        item-kind #(if (and (map? %) (nil? (:type %))
                            (contains? #{"system" "user" "assistant" "tool"}
                                       (:role %)))
                     :message (tag kinds (:type %) :unrecognized))]
    {:stage (tag {:initial :initial :follow-up :follow-up
                  :delegation :delegation} stage :unrecognized)
     :endpoint (tag {"/responses" :responses
                     "/chat/completions" :chat-completions
                     "/models" :models} path :unrecognized)
     :stream? (true? (:stream form))
     :provider (tag {"grok" :grok "xai" :grok "openai" :openai
                     "cloudflare" :cloudflare} (:name provider) :other)
     :model (tag {"grok-4.7" :grok "grok-4-1-fast-reasoning" :grok
                  "grok-4-1-fast-non-reasoning" :grok
                  "@cf/openai/gpt-oss-120b" :gpt-oss
                  "gpt-oss-120b" :gpt-oss}
                 (:model form) :other)
     :tool-count (saturated-count 64 (:tools form))
     :item-count (saturated-count 256 items)
     :tool-choice (if-not (contains? form :tool_choice) :omitted
                    (tag {"auto" :auto "none" :none "required" :required}
                         (:tool_choice form) :unrecognized))
     :item-types (vec (distinct (map item-kind items)))
     :opaque-state? (boolean
                      (some #(and (map? %) (contains? % :encrypted_content))
                            items))}))

(defn- field [value key]
  (when (map? value) (get value key (get value (name key)))))

(defn- scalar [value]
  (when (and (string? value) (<= (count value) 128)) value))

(defn- parameter [value]
  (let [value (scalar value)
        root (when value (first (.split ^String value "[.\\[]" 2)))]
    (tag {"tools" :tools "input" :input "messages" :messages
          "model" :model "max_tokens" :token-limit
          "max_completion_tokens" :token-limit
          "max_output_tokens" :token-limit "tool_choice" :tool-choice
          "temperature" :other "top_p" :other "stream" :other
          "store" :other "include" :other "max_turns" :other}
         root :unrecognized)))

(defn error-fields
  "Inspect only three shallow, length-bounded scalars; never error messages.
   Non-stream transport has already decoded JSON before this function runs."
  [body]
  (let [error (or (field body :error) body)]
    {:error-class
     (tag {"invalid_request_error" :invalid-request
           "authentication_error" :authentication
           "permission_error" :model-access
           "rate_limit_error" :rate-limit "server_error" :server}
          (scalar (field error :type)) :unrecognized)
     :error-code
     (tag {"invalid_function_parameters" :invalid-tool-schema
           "invalid_tool_schema" :invalid-tool-schema
           "unsupported_parameter" :unsupported-parameter
           "unsupported_value" :unsupported-parameter
           "missing_tool_output" :missing-tool-output
           "context_length_exceeded" :context-limit
           "model_not_found" :model-access
           "permission_denied" :model-access
           "insufficient_quota" :model-access
           "rate_limit_exceeded" :rate-limit}
          (scalar (field error :code)) :unrecognized)
     :parameter (parameter (field error :param))}))

(defn safe-error-fields
  "Revalidate exception metadata; never merge arbitrary exception data."
  [fields]
  (into {}
        (for [[key allowed]
              {:error-class #{:invalid-request :authentication :model-access
                              :rate-limit :server}
               :error-code #{:invalid-tool-schema :unsupported-parameter
                             :missing-tool-output :context-limit :model-access
                             :rate-limit}
               :parameter #{:tools :input :messages :model :token-limit
                            :tool-choice :other}}]
          (let [value (when (map? fields) (get fields key))]
            [key (if (and (keyword? value) (contains? allowed value))
                   value :unrecognized)]))))

(defn- shallow-json? [bytes]
  ;; Check depth before materializing even a byte-bounded JSON document.
  (with-open [parser (.createParser (JsonFactory.) ^bytes bytes)]
    (loop [depth 0]
      (if-let [token (.nextToken parser)]
        (let [depth (cond
                      (contains? #{JsonToken/START_OBJECT
                                   JsonToken/START_ARRAY} token) (inc depth)
                      (contains? #{JsonToken/END_OBJECT
                                   JsonToken/END_ARRAY} token) (dec depth)
                      :else depth)]
          (if (> depth 8) false (recur depth)))
        true))))

(defn stream-error-fields
  "Best effort only: 16 KiB, depth 8, one-second cooperative read budget.
   Existing socket timeout still bounds blocking reads. Caller owns cleanup."
  [body]
  (try
    (if (and (instance? InputStream body)
             (not (.isInterrupted (Thread/currentThread))))
      (let [bytes (cloudflare/read-bytes body 16384 1000)]
        (if (shallow-json? bytes)
          (error-fields (json/parse-string (String. ^bytes bytes "UTF-8")))
          (error-fields nil)))
      (error-fields nil))
    (catch Exception _ (error-fields nil))))

(defn status [value]
  (if (and (integer? value) (<= 100 value 599)) value :unrecognized))

(defn emit! [record]
  ;; No Throwable, exception text, provider objects or ambient request data.
  ;; A broken logging appender must not interfere with transport cleanup.
  (try (log/warn "ai-provider-diagnostic" record)
       (catch Exception _ nil)))
