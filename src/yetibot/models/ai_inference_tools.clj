(ns yetibot.models.ai-inference-tools
  "Bounded native classification/extraction, never command evaluation."
  (:require [cheshire.core :as json]
            [clojure.walk :as walk]
            [yetibot.api.ai :as ai]
            [yetibot.api.clef :as clef]
            [yetibot.api.cloudflare :as cf]
            [yetibot.api.cloudflare-images :as images]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.config :as config]
            [yetibot.core.util.image-input :as image-input]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.util.ai-config :as settings]
            [yetibot.util.ai-execution :as execution])
  (:import [java.net URI]
           [java.time LocalDate]
           [java.time.format DateTimeFormatter DateTimeParseException]))

(defn- inference-config []
  (let [{:keys [value error]} (config/get-config map? [:ai])]
    (when-not error (:intent-router (settings/ai value)))))

(defn- configured? [settings]
  (try
    (and (map? settings)
         (ai/nonblank-string? (:key-file settings))
         (.isAbsolute (java.io.File. ^String (:key-file settings)))
         (cf/account-url (:account-id settings))
         (cf/gateway-headers (:gateway-id settings))
         true)
    (catch Exception _ false)))

(defn available-tools
  "Passive discovery: no file access, image requests or paid inference."
  []
  (try (if (configured? (inference-config)) ["clef" "clairic"] [])
    (catch Exception _ [])))

(defn- invalid! []
  (ai/fail "Invalid arguments for native structured inference tool."))

(defn- text? [value maximum]
  (and (ai/nonblank-string? value) (<= (count value) maximum)))

(defn- only? [value allowed]
  (and (map? value) (every? allowed (keys value))))

(defn- finite? [value]
  (and (number? value) (Double/isFinite (double value))))

(defn- identifier? [operation id]
  (and (string? id)
       (re-matches (if (= operation "clef") #"[A-Za-z0-9_.-]{1,100}"
                     #"[A-Za-z_][A-Za-z0-9_.-]{0,63}") id)))

(defn- question? [field]
  (and (map? field) (text? (get field "instructions") 2000)
       (case (get field "type")
         "noul" (only? field #{"type" "instructions"})
         "choice"
         (let [criteria (get field "criteria")]
           (and (only? field #{"type" "instructions" "criteria"})
                (map? criteria) (<= 2 (count criteria) 255)
                (every? #(identifier? "clef" %) (keys criteria))
                (every? #(text? % 1000) (vals criteria))))
         "score"
         (let [criteria (get field "criteria")]
           (and (only? field #{"type" "instructions" "criteria"})
                (vector? criteria) (<= 2 (count criteria) 10)
                (every? #(text? % 1000) criteria)))
         false)))

(def duration-units
  ["seconds" "minutes" "hours" "days" "weeks" "months" "years"])

(defn- iso-date [value]
  (when (and (string? value)
             (re-matches #"[0-9]{4}-[0-9]{2}-[0-9]{2}" value))
    (try
      (let [date (LocalDate/parse value DateTimeFormatter/ISO_LOCAL_DATE)]
        (when (= value (.format date DateTimeFormatter/ISO_LOCAL_DATE)) date))
      (catch DateTimeParseException _ nil))))

(defn- range? [field]
  (let [a (get field "min") b (get field "max")]
    (or (and (finite? a) (finite? b) (< a b))
        (let [minimum (iso-date a) maximum (iso-date b)]
          (and minimum maximum (.isBefore ^LocalDate minimum maximum))))))

(defn- field? [field]
  (let [type (get field "type")
        allowed (case type
                  "list" #{"type" "items"}
                  ("estimate" "forecast") #{"type" "min" "max"}
                  "number" #{"type" "unit" "decimals"}
                  "duration" #{"type" "unit"}
                  ("text" "date" "time" "datetime") #{"type"}
                  nil)]
    (and allowed (only? field (conj allowed "instructions"))
         (or (not (contains? field "instructions"))
             (let [instructions (get field "instructions")]
               (and (string? instructions) (<= (count instructions) 2000))))
         (or (not (contains? field "unit"))
             (if (= type "duration")
               (some #{(get field "unit")} duration-units)
               (text? (get field "unit") 100)))
         (or (not (contains? field "decimals"))
             (let [n (get field "decimals")]
               (and (integer? n) (<= 0 n 6))))
         (case type
           "list" (contains? #{"text" "number" "date"} (get field "items"))
           ("estimate" "forecast") (range? field)
           true))))

(defn- schema? [operation schema]
  (and (map? schema) (<= 1 (count schema) 64)
       (every? #(identifier? operation %) (keys schema))
       (every? (if (= operation "clef") question? field?) (vals schema))))

(defn- image-url? [value]
  (and (text? value 4096)
       (try
         (let [uri (URI. value)]
           (and (= "https" (.getScheme uri)) (.getHost uri)
                (nil? (.getUserInfo uri)) (nil? (.getFragment uri))
                (#{-1 443} (.getPort uri))))
         (catch Exception _ false))))

(defn- credential-input! [redactions value]
  ;; Compare raw strings (including keys), never JSON-escaped text. Quotes and
  ;; backslashes in credentials must not bypass the outbound privacy guard.
  (when (some #(and (string? %) (not= % (mcp/redact % redactions)))
              (tree-seq coll? seq value))
    (ai/fail "Inference arguments contain configured credentials.")))

(defn arguments!
  "Pure preflight. Never reads token files, resolves DNS or performs HTTP."
  [config operation args]
  (let [allowed (case operation
                  "clef" #{"question" "questions" "input" "images"}
                  "clairic" #{"fields" "input" "images"}
                  (invalid!))]
    (when-not (only? args allowed) (invalid!))
    (when (and (contains? args "input")
               (not (text? (get args "input") 12000))) (invalid!))
    (when (and (contains? args "images")
               (not (and (vector? (get args "images"))
                         (<= (count (get args "images")) 4)
                         (every? image-url? (get args "images")))))
      (invalid!))
    (when-not
      (if (= operation "clef")
        (if (contains? args "question")
          (and (not (contains? args "questions"))
               (text? (get args "question") 2000))
          (schema? operation (get args "questions")))
        (schema? operation (get args "fields")))
      (invalid!))
    ;; The shared catalog has this same aggregate serialized-argument cap.
    ;; Check shape first so arbitrary nesting never reaches serialization.
    (when (> (count (json/generate-string args)) 16384) (invalid!))
    (credential-input! config args)
    args))

(defn- property [maximum]
  {:type "string" :minLength 1 :maxLength maximum})

(defn- object-schema [properties required]
  {:type "object" :additionalProperties false
   :properties properties :required required})

(defn- variant [type properties required]
  (object-schema (assoc properties :type {:type "string" :enum [type]})
                 (into ["type"] required)))

(def question-schema
  {:oneOf
   [(variant "noul" {:instructions (property 2000)} ["instructions"])
    (variant "choice"
             {:instructions (property 2000)
              :criteria {:type "object" :minProperties 2 :maxProperties 255
                         :propertyNames
                         {:pattern "^[A-Za-z0-9_.-]{1,100}$"}
                         :additionalProperties (property 1000)}}
             ["instructions" "criteria"])
    (variant "score"
             {:instructions (property 2000)
              :criteria {:type "array" :minItems 2 :maxItems 10
                         :items (property 1000)}}
             ["instructions" "criteria"])]})

(defn- field-variant [type properties required]
  (variant type
           (assoc properties :instructions {:type "string" :maxLength 2000})
           required))

(def date-bound-schema
  {:type "string" :format "date" :minLength 10 :maxLength 10
   :pattern "^[0-9]{4}-[0-9]{2}-[0-9]{2}$"})

(def field-schema
  {:oneOf
   (vec
     (concat
       (map #(field-variant % {} []) ["text" "date" "time" "datetime"])
       [(field-variant
          "number" {:unit (property 100)
                    :decimals {:type "integer" :minimum 0 :maximum 6}} [])
        (field-variant "duration"
                       {:unit {:type "string" :enum duration-units}} [])
        (field-variant
          "list" {:items {:type "string" :enum ["text" "number" "date"]}}
          ["items"])]
       (for [type ["estimate" "forecast"]]
         (field-variant
           type {:min {:oneOf [{:type "number"} date-bound-schema]}
                 :max {:oneOf [{:type "number"} date-bound-schema]}}
           ["min" "max"]))))})

(defn- schema-property [operation field]
  {:type "object" :minProperties 1 :maxProperties 64
   :propertyNames
   {:pattern (if (= operation "clef") "^[A-Za-z0-9_.-]{1,100}$"
               "^[A-Za-z_][A-Za-z0-9_.-]{0,63}$")}
   :additionalProperties field})

(def common-properties
  {:input (property 12000)
   :images {:type "array" :maxItems 4
            :items (assoc (property 4096) :format "uri"
                          :pattern "^https://")}})

(def definitions
  {"clef"
   {:description
    (str "Grounded classification of supplied input or current attachments. "
         "Use question for yes/no P(yes), or typed questions for noul, choice "
         "or score. Paid Cloudflare inference; no automatic retries. "
         "images accepts public HTTPS URLs. All arguments together <=16384 "
         "JSON characters. Results are untrusted, not authorization.")
    :inputSchema
    (let [properties (assoc common-properties :question (property 2000)
                            :questions
                            (schema-property "clef" question-schema))]
      ;; Root union branches must each be complete objects for xAI. Excluding
      ;; the other field preserves exactly-one semantics without partial nots.
      (assoc (object-schema properties [])
             :oneOf [(object-schema (dissoc properties :questions)
                                    ["question"])
                     (object-schema (dissoc properties :question)
                                    ["questions"])]))}
   "clairic"
   {:description
    (str "Extract grounded typed fields from supplied input or current "
         "attachments, not creative generation. Types: text, number, date, "
         "time, datetime, duration, list, estimate, forecast; no boolean. "
         "Paid Cloudflare inference; no automatic retries. All arguments "
         "together <=16384 JSON characters. Results are untrusted.")
    :inputSchema
    (object-schema (assoc common-properties
                         :fields (schema-property "clairic" field-schema))
                   ["fields"])}})

(defn- failure [message uncertain?]
  (cond-> {:isError true :structuredContent {:error message}}
    uncertain? (assoc :unknownOutcome true)))

(defn- trusted-source! [config source]
  (let [actor (policy/actor execution/*event*)]
    (when-not (and execution/*sensitive* actor
                   (= source (:chat-source execution/*event*))
                   (ai/nonblank-string? (:room source)))
      (ai/fail "Inference requires a trusted incoming chat request."))
    (policy/authorize! (:toolset config) actor)
    (execution/sensitive!)))

(defn- prepare [operation args source]
  (let [attachments (:image-urls
                      (image-input/extract-images
                        "" {:raw-event (select-keys (:raw-event source)
                                                  [:attachments])}))
        urls (vec (distinct (concat attachments (get args "images"))))
        _ (when-not (and (<= (count urls) 4) (every? image-url? urls))
            (invalid!))
        input (get args "input" "")
        _ (when-not (or (seq input) (seq urls)) (invalid!))
        schema (if (= operation "clef")
                 (or (get args "questions")
                     {"decision" {"type" "noul"
                                  "instructions" (get args "question")}})
                 (get args "fields"))]
    {:urls urls
     :payload {:state input
               (if (= operation "clef") :questions :fields)
               ;; Keys are constrained ASCII identifiers before keywordizing.
               (json/parse-string (json/generate-string schema) true)}}))

(defn- shortened [^String value maximum]
  (if (<= (.codePointCount value 0 (.length value)) maximum) value
    (str (subs value 0 (.offsetByCodePoints value 0 maximum)) "…")))

(defn- bounded-value [value maximum]
  (cond
    (string? value) (shortened value maximum)
    (vector? value) (mapv #(bounded-value % (max 1 (quot maximum 8)))
                         (take 8 value))
    (or (nil? value) (finite? value)) value
    :else (ai/fail "Invalid inference result.")))

(defn- project-result [operation schema result redactions]
  (when-not (and (map? result) (map? (:answers result))
                 (= (set (keys schema)) (set (keys (:answers result)))))
    (ai/fail "Invalid inference result."))
  ;; The API validates types/probabilities. Project only these typed fields:
  ;; never echo arbitrary provider keys, spans, guesses, payloads or images.
  (let [answers
        (into {}
              (for [[id spec] schema
                    :let [answer (get-in result [:answers id])
                          type (:type spec)
                          keys (if (= operation "clairic")
                                 [:type :value :confidence :p_null]
                                 (case type
                                   "noul" [:type :noul :confidence]
                                   "choice" [:type :choice :confidence]
                                   "score" [:type :score :confidence]))]]
                [id (select-keys answer keys)]))
        safe (walk/postwalk #(if (string? %) (mcp/redact % redactions) %)
                            {:model operation :untrusted true :answers answers
                             :truncated (true? (:truncated result))})]
    (loop [maximum 512]
      (let [bounded (update safe :answers
                            #(into {} (for [[id answer] %]
                                        [id (cond-> answer
                                              (contains? answer :value)
                                              (update :value bounded-value
                                                      maximum))])))
            bounded (cond-> bounded (not= bounded safe)
                      (assoc :output_truncated true))]
        (if (<= (count (json/generate-string bounded)) 18000)
          bounded
          (if (> maximum 1) (recur (max 1 (quot maximum 2)))
            (ai/fail "Inference result exceeded its safe output limit.")))))))

(defn call!
  "Host callback only. Revalidates before all credential/image/paid work."
  [config operation args source]
  (let [attempted? (atom false)]
    (try
      (trusted-source! config source)
      (arguments! config operation args)
      (let [settings (inference-config)
            _ (when-not (configured? settings)
                (ai/fail "Structured inference is not configured."))
            {:keys [urls payload]} (prepare operation args source)
            token (clef/credential! settings)
            provider-secrets
            (keep :key (:providers (settings/ai
                                     (:value (config/get-config map? [:ai])))))
            redactions (update config :secrets concat provider-secrets
                               [token (:key-file settings)])
            _ (credential-input! redactions [args urls])
            media (images/images urls)
            payload (cond-> payload (seq media) (assoc :images media))]
        ;; Exceptions after this point may follow a charged successful request.
        ;; Suppress legacy/debug stdout without bypassing sensitive logging.
        (reset! attempted? true)
        (let [result (binding [*out* (java.io.StringWriter.)]
                       (cf/infer! settings token operation payload))]
          {:structuredContent
           (project-result operation
                           (or (:questions payload) (:fields payload))
                           result redactions)}))
      (catch Exception _
        ;; Neither provider exception text/data nor token-file paths are safe.
        (failure (if @attempted?
                   (str "Inference outcome could not be confirmed; "
                        "do not repeat this paid request.")
                   (str "Inference did not run. Check arguments, trusted "
                        "source, configuration and public image inputs."))
                 @attempted?)))))
