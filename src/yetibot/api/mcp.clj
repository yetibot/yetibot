(ns yetibot.api.mcp
  "Short-lived MCP sessions for explicitly allowed tools. No model controls
   connection settings, credentials, executable names or the allowed tool list."
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
  (s/and (s/keys :req-un [::name ::tools]
                 :opt-un [::url ::command ::args ::env ::headers ::github-api-token])
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
        (.initialize client)
        session)
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
            (when-not (every? #(contains? by-name %) (:tools server))
              (fail "An allowed MCP tool was not advertised by its server. Check the configured tool names."))
            (mapv by-name (:tools server))))))
    (catch Exception _ (fail (str "Could not list allowed tools on MCP server " (:name server) ".")))))

(defn call-tool [{:keys [server client]} tool arguments]
  (when-not (some #{tool} (:tools server))
    (fail "This MCP tool is not allowed by the server configuration."))
  (try
    (as-data (.callTool ^McpSyncClient client (McpSchema$CallToolRequest. tool arguments nil)))
    (catch Exception _
      ;; Do not retry: a future allowed tool could have already changed something.
      {:isError true :unknownOutcome true :content [{:type "text"
                               :text (str "The tool call on " (:name server)
                                          " failed or timed out. Its outcome is unknown; do not repeat it.")}]})))

(defn with-tools
  "Connect per request, expose only configured tools, and always close sessions."
  [config f]
  (let [sessions (atom [])]
    (try
      (doseq [server (:servers config)]
        (swap! sessions conj (connect! server (number-option (:timeout-ms config) 30000))))
      (let [tools (vec (mapcat (fn [session] (map #(assoc % :session session) (list-tools session)))
                               @sessions))]
        (f tools))
      (finally (doseq [session (reverse @sessions)] (close! session))))))

(defn- header-credentials [{:keys [headers tools]}]
  (keep (fn [[k v]]
          (let [header (when (or (keyword? k) (string? k)) (string/lower-case (name k)))
                public? (or (and (= "x-mcp-readonly" header) (string? v)
                                 (contains? #{"true" "false"} (string/lower-case v)))
                            (and (= "x-mcp-tools" header) (seq tools)
                                 (= v (string/join "," tools))))]
            (when-not public? v)))
        headers))

(defn redact
  "Remove configured credentials from data before it reaches the model or chat."
  [text config]
  (let [credentials (->> (concat (:secrets config)
                                 (mapcat #(concat [(:github-api-token %)]
                                                  (vals (:env %)) (header-credentials %))
                                         (concat (:servers config) (:redaction-servers config))))
                         (filter text?)
                         (mapcat #(vector % (string/replace % #"(?i)^Bearer\s+" "")))
                         (filter text?)
                         distinct
                         (sort-by count >))]
    (reduce #(string/replace %1 %2 "[redacted]") text credentials)))
