(ns yetibot.models.ai-kroki-tools
  "Typed adapters for the separately released Kroki plugin. No command evaluation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.util.ai-execution :as execution]))

(defn available-tools []
  ;; App/plugin releases are independent. Old plugin artifacts must not expose
  ;; nonfunctional tools while the actual published dependency is being adopted.
  (if (io/resource "yetibot_kroki/client.clj") ["kroki_types" "kroki_render"] []))

(defn client-call [operation & args]
  (if-let [f (requiring-resolve (symbol "yetibot-kroki.client" operation))]
    (apply f args)
    (ai/fail "The installed Kroki plugin must be upgraded.")))

(def definitions
  {"kroki_types"
   {:description
    (str "Discover diagram engines and versions from the configured Kroki service. "
         "Optional type selects an engine. Page with after/limit (default 20). "
         "stale means cached metadata, not current availability. Formats/options "
         "are not a discovered schema. No rendering or model inference.")
    :annotations {:readOnlyHint true :destructiveHint false
                  :idempotentHint true :openWorldHint true}
    :inputSchema
    {:type "object" :additionalProperties false
     :properties {:type {:type "string" :pattern "^[a-z][a-z0-9_-]{0,63}$"}
                  :after {:type "string" :maxLength 64}
                  :limit {:type "integer" :minimum 1 :maximum 50}}}}
   "kroki_render"
   {:description
    (str "Render literal diagram source using Kroki, not an LLM or a command interpreter. "
         "type is an engine such as mermaid, graphviz, d2 or one from kroki_types. "
         "Omit format for PNG/SVG negotiation; explicit formats depend on the engine. "
         "options are engine-specific scalar diagram options, e.g. {layout: tala}. "
         "Source is sent to the operator-configured renderer (default public kroki.io). "
         "Never include credentials. Returns a public temporary artifact, not private "
         "or permanent storage. The host delivers the artifact to chat even if your "
         "answer omits its URL. Syntax/options failures are errors, not successful images.")
    :annotations {:readOnlyHint false :destructiveHint false
                  :idempotentHint false :openWorldHint true}
    :max-argument-length 2100000
    :inputSchema
    {:type "object" :additionalProperties false :required ["type" "source"]
     :properties {:type {:type "string" :pattern "^[a-z][a-z0-9_-]{0,63}$"}
                  :source {:type "string" :minLength 1 :maxLength 262144}
                  :format {:type "string" :enum ["auto" "png" "svg" "jpeg" "pdf" "txt" "utxt" "base64"]}
                  :options {:type "object" :maxProperties 32
                            :additionalProperties {:type ["string" "number" "boolean"]}}}}}})

(defn- invalid! [] (ai/fail "Invalid Kroki tool arguments."))
(defn- type? [s]
  (and (string? s) (boolean (re-matches #"[a-z][a-z0-9_-]{0,63}" s))))

(defn- render-request [args]
  (into {} (map (fn [[k v]] [(keyword k) v])) args))

(defn arguments! [config operation args]
  (when-not (map? args) (invalid!))
  (case operation
    "kroki_types"
    (when-not (and (every? #{"type" "after" "limit"} (keys args))
                   (every? #(or (not (contains? args %)) (type? (get args %)))
                           ["type" "after"])
                   (or (not (contains? args "limit"))
                       (and (integer? (get args "limit")) (<= 1 (get args "limit") 50))))
      (invalid!))
    "kroki_render"
    (do
      (when-not (and (every? #{"type" "source" "format" "options"} (keys args))
                     (type? (get args "type")) (ai/nonblank-string? (get args "source")))
        (invalid!))
      (when-not (= args (walk/postwalk #(if (string? %) (mcp/redact % config) %) args))
        (ai/fail "Diagram source/options contain configured credentials."))
      (client-call "validate-render!" (render-request args)))
    (invalid!))
  args)

(defn call! [config operation args source]
  (try
    (let [actor (policy/actor execution/*event*)]
      (when-not (and execution/*sensitive* actor
                     (= source (:chat-source execution/*event*))
                     (ai/nonblank-string? (:room source)))
        (ai/fail "Kroki requires a trusted chat request."))
      (execution/sensitive!)
      (policy/authorize! (:toolset config) actor)
      (arguments! config operation args)
      (let [result (if (= operation "kroki_types")
                     (client-call "types!" (get args "type"))
                     (client-call "render!" (render-request args)))]
        (if (:result/error result)
          (let [diagnostic (cond-> (select-keys (:result/public-error result)
                                               [:service :stage :kind :http-status :line :column])
                             (get args "type") (assoc :type (get args "type")))]
            (when (:kroki-errors config)
              (swap! (:kroki-errors config) conj diagnostic))
            {:isError true
             :structuredContent {:error (:result/error result) :diagnostic diagnostic}})
          (let [data (if (= operation "kroki_types")
                       (let [data (select-keys (:result/data result)
                                               [:engines :server-version :checked-at :stale])
                             data (update data :engines
                                          #(mapv (fn [row] (select-keys row [:type :version])) %))
                             after (get args "after") limit (get args "limit" 20)
                             rows (filterv #(or (nil? after) (pos? (compare (:type %) after)))
                                           (:engines data))
                             page (vec (take limit rows))
                             more? (> (count rows) (count page))]
                         (assoc data :engines page :has_more more?
                                :next_after (when more? (:type (last page)))))
                       (select-keys (:result/public-data (public/projection result))
                                    [:type :format :mime-type :bytes :id :url :ephemeral]))
                data (walk/postwalk #(if (string? %) (mcp/redact % config) %) data)]
            (when (and (= operation "kroki_render") (not (ai/nonblank-string? (:url data))))
              (ai/fail "Kroki returned no reviewed artifact."))
            (when (and (= operation "kroki_render") (:kroki-artifacts config))
              (swap! (:kroki-artifacts config) conj data))
            {:structuredContent (assoc data :untrusted true)}))))
    (catch Exception _
      {:isError true :structuredContent {:error "Kroki could not complete this request."}})))
