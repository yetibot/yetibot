(ns yetibot.api.mcp
  "Short-lived MCP sessions for configured servers and discovered tools.
   Models never control connections, credentials, executables or allowlists."
  (:require [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [yetibot.core.config :refer [get-config]]
            [yetibot.util.ai-config :as settings])
  (:import [io.modelcontextprotocol.client McpClient McpSyncClient]
           [io.modelcontextprotocol.client.transport HttpClientStreamableHttpTransport
            ServerParameters StdioClientTransport]
           [io.modelcontextprotocol.json McpJsonDefaults McpJsonMapper]
           [io.modelcontextprotocol.client.transport.customizer McpAsyncHttpClientRequestCustomizer]
           [java.lang.reflect Proxy InvocationHandler InvocationTargetException Method]
           [java.security AccessController PrivilegedAction]
           [reactor.core.publisher Mono]
           [reactor.core.scheduler Scheduler Schedulers]
           [io.modelcontextprotocol.spec McpClientTransport McpSchema$CallToolRequest McpSchema$Implementation]
           [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest]
           [java.time Duration]
           [java.util.function Consumer]))

(defn fail [message] (throw (ex-info message {:ai/error true})))
(defn text? [value] (and (string? value) (not (string/blank? value))))

(defn number-option [value default]
  (if (nil? value) default
    (try (Long/parseLong (str value)) (catch Exception _ nil))))

(defn- bounded-number? [minimum maximum value]
  (let [n (number-option value nil)] (and n (<= minimum n maximum))))

(defn- endpoint? [value]
  (and (text? value)
       (try
         (let [uri (URI. value)]
           (and (#{"http" "https"} (.getScheme uri)) (.getHost uri)
                (nil? (.getUserInfo uri)) (nil? (.getQuery uri)) (nil? (.getFragment uri))))
         (catch Exception _ false))))

(s/def ::name (s/and text? #(not (re-find #"\s" %))))
(s/def ::url endpoint?)
(s/def ::command text?)
(s/def ::args (s/coll-of string? :kind vector?))
(s/def ::env (s/map-of (s/or :string text? :keyword keyword?) string?))
(s/def ::headers (s/map-of (s/or :string text? :keyword keyword?) text?))
(s/def ::github-api-token text?)
(s/def ::tools (s/and (s/coll-of text? :kind vector? :min-count 1 :max-count 64)
                    #(apply distinct? %)))
(s/def ::server
  (s/and (s/keys :req-un [::name]
                 :opt-un [::tools ::url ::command ::args ::env ::headers
                          ::github-api-token])
         #(if (:url %) (not-any? (partial contains? %) [:command :args :env])
              (and (:command %) (not (contains? % :headers))))))
(s/def ::servers (s/and (s/coll-of ::server :kind vector? :max-count 16)
                       #(or (empty? %) (apply distinct? (map :name %)))))
(s/def ::max-tool-calls #(bounded-number? 1 20 %))
(s/def ::timeout-ms #(bounded-number? 100 120000 %))
(s/def ::settings (s/keys :req-un [::servers] :opt-un [::max-tool-calls ::timeout-ms]))
(s/def ::config #(s/valid? ::settings (settings/mcp %)))

(defn config []
  (let [{:keys [value error]} (get-config ::config [:ai :mcp])]
    (when (and error (not= :not-found error))
      (fail "AI tool configuration is invalid. Check MCP server connections and allowed tool names."))
    (settings/mcp value)))

(defn- string-keys [m] (into {} (map (fn [[k v]] [(name k) v]) m)))
(defn- ignore-messages [] (reify Consumer (accept [_ _])))

(defn- application-json-mapper []
  (let [mapper (McpJsonDefaults/getMapper)]
    ;; HttpClient callbacks have an empty access-control context once Clojail
    ;; installs SecurityManager. Only the SDK's fixed JSON codec methods need
    ;; application permissions; never run model code inside this boundary.
    (Proxy/newProxyInstance
      (.getClassLoader McpJsonMapper) (into-array Class [McpJsonMapper])
      (reify InvocationHandler
        (invoke [_ _ method arguments]
          (AccessController/doPrivileged
            (reify PrivilegedAction
              (run [_]
                (try (.invoke ^Method method mapper arguments)
                     (catch InvocationTargetException e
                       (throw (.getCause e))))))))))))

(defn- http-transport [url headers timeout-ms]
  (let [scheduler (Schedulers/newSingle "yetibot-mcp-http" true)]
    (try
      ;; Create the daemon worker in application context, not lazily inside a
      ;; restricted HTTP callback. Each session owns and disposes its worker.
      (.block (.subscribeOn (Mono/fromRunnable (fn [])) scheduler)
              (Duration/ofMillis timeout-ms))
      (let [uri (URI. url)
            origin (str (.getScheme uri) "://" (.getRawAuthority uri))
            path (if (string/blank? (.getRawPath uri)) "/" (.getRawPath uri))
            request (doto (HttpRequest/newBuilder)
                      (.timeout (Duration/ofMillis timeout-ms)))]
        (doseq [[k v] (string-keys headers)] (.header request k v))
        (let [transport
              (-> (HttpClientStreamableHttpTransport/builder origin)
                  (.endpoint path)
                  (.clientBuilder (doto (HttpClient/newBuilder)
                                    (.followRedirects HttpClient$Redirect/NEVER)
                                    (.connectTimeout (Duration/ofMillis timeout-ms))))
                  (.jsonMapper (application-json-mapper))
                  (.addAsyncHttpRequestCustomizer
                    (reify McpAsyncHttpClientRequestCustomizer
                      (customize [_ builder _ _ _ _]
                        ;; Follow-up requests must not inherit HttpClient's
                        ;; restricted callback context either.
                        (.publishOn (Mono/just builder) scheduler))))
                  (.requestBuilder request)
                  (.resumableStreams false)
                  (.openConnectionOnStartup false)
                  (.maxResponseSize 1048576)
                  (.build))]
          (.setExceptionHandler transport (ignore-messages))
          {:transport transport :scheduler scheduler}))
      (catch Throwable e
        (.dispose scheduler)
        (throw e)))))

(defn- transport [{:keys [url command args env headers]} timeout-ms]
  (if url
    (http-transport url headers timeout-ms)
    (let [params (-> (ServerParameters/builder command)
                     (.args ^java.util.List (or args []))
                     (.env (string-keys env))
                     (.build))
          ;; The SDK normally inherits the whole environment. Clear it first;
          ;; ServerParameters adds only its small safe list and explicit :env.
          transport (proxy [StdioClientTransport] [params (McpJsonDefaults/getMapper) 1048576]
                      (getProcessBuilder []
                        (let [builder (ProcessBuilder. ^java.util.List [])]
                          (.clear (.environment builder))
                          builder)))]
      (.setStdErrorHandler transport (ignore-messages))
      {:transport transport})))

(defn close! [{:keys [client transport scheduler]}]
  (try
    (if client
      (.closeGracefully ^McpSyncClient client)
      (when transport
        (.block (.closeGracefully ^McpClientTransport transport) (Duration/ofSeconds 5))))
    (catch Exception _ nil)
    (finally
      (when scheduler (.dispose ^Scheduler scheduler)))))

(declare metadata-text)

(def ^:dynamic ^:private *discovery-config* nil)

(defn connect! [server timeout-ms]
  (let [holder (atom nil)]
    (try
      (let [resources (transport server timeout-ms)
            transport (:transport resources)
            _ (reset! holder resources)
            client (-> (McpClient/sync transport)
                       (.requestTimeout (Duration/ofMillis timeout-ms))
                       (.initializationTimeout (Duration/ofMillis timeout-ms))
                       (.clientInfo (McpSchema$Implementation. "yetibot" "1.0"))
                       (.enableCallToolSchemaCaching false)
                       (.build))
            session (assoc resources :server server :client client)]
        (reset! holder session)
        (let [result (.initialize client)
              text (.instructions result)]
          ;; Keep only public bounded text, never the initialize result itself.
          (cond-> session
            (text? text)
            (assoc :instructions
                   (assoc (metadata-text
                            text (or *discovery-config*
                                     {:servers [server]}) 20000)
                          :status "UNTRUSTED/NOT APPLIED TO MODEL")))))
      (catch Exception _
        (close! @holder)
        (fail (str "Could not connect to MCP server " (:name server) ". Check its connection and credentials."))))))

(defn- as-data [value]
  (json/parse-string (.writeValueAsString (McpJsonDefaults/getMapper) value) true))

(defn list-tools [{:keys [server client]}]
  (try
    (loop [cursor nil seen #{} tools [] pages 0]
      (when (>= pages 20) (fail "MCP tool discovery exceeded its page limit."))
      (let [page (.listTools ^McpSyncClient client ^String cursor)
            next-cursor (.nextCursor page)
            tools (into tools (map as-data (.tools page)))]
        (if (text? next-cursor)
          (do
            (when (contains? seen next-cursor) (fail "MCP tool discovery did not advance."))
            (recur next-cursor (conj seen next-cursor) tools (inc pages)))
          (let [by-name (into {} (map (juxt :name identity) tools))]
            (when-not (= (count tools) (count by-name))
              (fail "MCP tool discovery returned duplicate names."))
            (if (contains? server :tools)
              (do
                (when-not (every? #(contains? by-name %) (:tools server))
                  (fail "An allowed MCP tool was not advertised."))
                (mapv by-name (:tools server)))
              tools)))))
    (catch Exception _ (fail (str "Could not list allowed tools on MCP server " (:name server) ".")))))

(defn- call-connected-tool [{:keys [server client]} tool arguments]
  (when-not (some #{tool} (:tools server))
    (fail "This MCP tool is not allowed by the server configuration."))
  (try
    (as-data (.callTool ^McpSyncClient client (McpSchema$CallToolRequest. tool arguments nil)))
    (catch Exception _
      ;; Do not retry: a future allowed tool could have already changed something.
      {:isError true :unknownOutcome true :content [{:type "text"
                               :text (str "The tool call on " (:name server)
                                          " failed or timed out. Its outcome is unknown; do not repeat it.")}]})))

(defn call-tool [{:keys [server lazy? timeout-ms] :as session}
                 tool arguments]
  (when-not (some #{tool} (:tools server))
    (fail "This MCP tool is not allowed by the server configuration."))
  (if-not lazy?
    (call-connected-tool session tool arguments)
    ;; Cache reads never connect. Only an authorized actual call opens a new
    ;; session; schema discovery is not repeated and calls are never retried.
    (if-let [connected (try (connect! server timeout-ms)
                            (catch Exception _ nil))]
      (try (call-connected-tool connected tool arguments)
           (finally (close! connected)))
      {:isError true
       :content [{:type "text"
                  :text "This MCP server is unavailable. No call was made."}]})))

(defn- with-strict-tools
  "Discover per request, apply optional allowlists, and always close sessions."
  [config f]
  (let [sessions (atom [])]
    (binding [*discovery-config* config]
      (try
        (doseq [server (:servers config)]
          (swap! sessions conj
                 (connect! server
                           (number-option (:timeout-ms config) 30000))))
        (let [tools
              (vec (mapcat
                     (fn [session]
                       (let [tools (list-tools session)
                             session (assoc-in session [:server :tools]
                                               (mapv :name tools))]
                         (map #(assoc % :session session) tools)))
                     @sessions))]
          ;; Metadata also covers servers advertising zero eligible tools.
          (f (with-meta tools
               {::instructions
                (mapv #(cond-> {:server (get-in % [:server :name])}
                         (:instructions %)
                         (assoc :instructions (:instructions %)))
                      @sessions)})))
        (finally
          (doseq [session (reverse @sessions)] (close! session)))))))

(defn with-tools
  "Strict discovery by default. Tolerant mode uses bounded public metadata
   and lazy request-scoped connections; failures cannot hide native tools."
  [config f]
  (if (:tolerant? config)
    ((requiring-resolve 'yetibot.models.ai-mcp-catalog/with-tools) config f)
    (with-strict-tools config f)))

(defn- header-credentials [{:keys [headers tools]}]
  (keep (fn [[k v]]
          (let [header (when (or (keyword? k) (string? k)) (string/lower-case (name k)))
                public? (or (and (= "x-mcp-readonly" header) (string? v)
                                 (contains? #{"true" "false"} (string/lower-case v)))
                            (and (= "x-mcp-tools" header) (seq tools)
                                 (= v (string/join "," tools))))]
            (when-not public? v)))
        headers))

(defn credentials
  "Configured redaction values, including stripped Bearer tokens."
  [config]
  (->> (concat (:secrets config)
               (mapcat #(concat [(:github-api-token %)] (vals (:env %))
                                (header-credentials %))
                       (concat (:servers config) (:redaction-servers config))))
       (filter text?)
       (mapcat #(vector % (string/replace % #"(?i)^Bearer\s+" "")))
       (filter text?)
       distinct
       (sort-by count >)))

(defn redact
  "Remove configured credentials from data before it reaches the model or chat."
  [text config]
  (reduce #(string/replace %1 %2 "[redacted]") text (credentials config)))

(defn metadata-redact
  "Redact known credentials, including JSON-escaped copies, before bounding."
  [text config]
  (letfn [(character-pattern [c]
            (let [encoded (json/generate-string (str c))
                  escaped (subs encoded 1 (dec (count encoded)))
                  variants (cond-> [(str c) escaped]
                             (= c \/) (conj "\\/"))]
              (str "(?:"
                   (string/join "|" (map #(java.util.regex.Pattern/quote %)
                                          (distinct variants)))
                   "|(?i:"
                   (java.util.regex.Pattern/quote
                     (format "\\u%04x" (int c))) "))")))]
    (reduce
      (fn [text secret]
        ;; Per-character alternatives handle mixed raw/JSON/unicode escapes.
        (string/replace text
                        (re-pattern (string/join (map character-pattern secret)))
                        "[redacted]"))
      text (credentials config))))

(defn metadata-text [text config maximum]
  (let [text (metadata-redact text config)]
    (cond-> {:text (subs text 0 (min maximum (count text)))}
      (> (count text) maximum) (assoc :truncated true))))
