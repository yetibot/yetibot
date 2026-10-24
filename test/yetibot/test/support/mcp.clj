(ns yetibot.test.support.mcp
  (:require [cheshire.core :as json]
            [clojure.string :as string])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]
           [java.util.concurrent Executors]))

(def search-tool {:name "web_search" :description "Search the web and return source URLs."
                  :inputSchema {:type "object" :properties {:query {:type "string"}} :required ["query"]}})
(def other-tool (assoc search-tool :name "delete_all"))

(defn rpc-result [request]
  (case (:method request)
    "initialize" {:protocolVersion (get-in request [:params :protocolVersion])
                  :capabilities {:tools {}} :serverInfo {:name "test-search" :version "1.0"}}
    "tools/list" (if (get-in request [:params :cursor])
                   {:tools [other-tool]}
                   {:tools [search-tool] :nextCursor "page-two"})
    "tools/call" {:content [{:type "text" :text "Latest news: https://source.test/news"}]
                  :structuredContent {:results [{:title "News" :url "https://source.test/news"}]}
                  :isError false}
    nil))

(defn with-http-server [handler f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        executor (Executors/newCachedThreadPool)]
    (.setExecutor server executor)
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ exchange]
          (try
            (let [raw (slurp (.getRequestBody exchange))
                  request {:path (.getPath (.getRequestURI exchange))
                           :method (.getRequestMethod exchange)
                           :headers (into {} (map (fn [[k v]] [(string/lower-case k) (first v)]) (.getRequestHeaders exchange)))
                           :body (when (seq raw) (json/parse-string raw true))}
                  {:keys [status body headers sse stream] :or {status 200}}
                  (handler request)
                  payload (when body (json/generate-string body))
                  payload (if sse (str "event: message\ndata: " payload "\n\n") payload)]
              (doseq [[k v] (merge (when (or payload stream)
                                        {"Content-Type"
                                         (if (or sse stream)
                                           "text/event-stream"
                                           "application/json")}) headers)]
                (.set (.getResponseHeaders exchange) k v))
              (cond
                stream
                (do (.sendResponseHeaders exchange status 0)
                    (with-open [out (.getResponseBody exchange)] (stream out)))
                payload
                (let [bytes (.getBytes payload "UTF-8")]
                  (.sendResponseHeaders exchange status (count bytes))
                  (with-open [out (.getResponseBody exchange)] (.write out bytes)))
                :else (.sendResponseHeaders exchange status -1)))
            (finally (.close exchange))))))
    (.start server)
    (try (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
         (finally (.stop server 0) (.shutdownNow executor)))))

(defn rpc-handler [requests sse]
  (fn [request]
    (swap! requests conj request)
    (case (:method request)
      "GET" {:status 405}
      "DELETE" {:status 204}
      "POST" (if (get-in request [:body :id])
               {:headers {"Mcp-Session-Id" "test-session"}
                :sse sse
                :body {:jsonrpc "2.0" :id (get-in request [:body :id])
                       :result (rpc-result (:body request))}}
               {:status 202}))))
