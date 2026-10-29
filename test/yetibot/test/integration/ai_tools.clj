(ns yetibot.test.integration.ai-tools
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as api]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as commands]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.models.ai-context :as context]
            [yetibot.test.models.ai-tool-policy :refer [actor]]
            [yetibot.test.support.ai-execution :as chat]
            [yetibot.test.support.mcp :as support])
  (:import [java.util Base64]))

(def base-sha (string/join (repeat 40 "a")))
(def commit-sha (string/join (repeat 40 "b")))
(def reader-tools ["get_commit" "get_file_contents"])
(def writer-tools ["create_branch" "push_files" "create_pull_request"])

(defn tool [name]
  {:name name :description name :inputSchema {:type "object" :properties {}}})

(defn fixture-handler [requests pushed]
  (fn [{:keys [path method body] :as request}]
    (swap! requests conj request)
    (if (= path "/v1/chat/completions")
      (let [search? (some #(and (= "user" (:role %))
                                (string/includes? (:content %) "Search")) (:messages body))
            read? (some #(and (= "user" (:role %))
                              (string/includes? (:content %) "Read"))
                        (:messages body))
            done? (= "tool" (:role (last (:messages body))))
            answer (if read? "Commit loaded."
                         "Source: https://source.test/news")
            suffix (cond search? "web_search" read? "get_commit"
                         :else "stage_changes")
            name (some #(let [n (get-in % [:function :name])]
                          (when (string/ends-with? n suffix) n))
                       (:tools body))
            arguments (cond
                        search? {"query" "news"}
                        read? {"owner" "owner" "repo" "repo" "sha" "main"}
                        :else {"owner" "owner" "repo" "repo" "base" "main"
                         "title" "Fix a" "body" "A focused fix"
                         "files" [{"path" "src/a.clj" "content" "new\n"}]})]
        {:body {:choices [{:message (if done?
                                      {:role "assistant" :content answer}
                                      {:role "assistant" :content nil
                                       :tool_calls [{:id "call-1" :type "function"
                                                     :function {:name name
                                                                :arguments (json/generate-string arguments)}}]})}]}})
      (case method
        "GET" {:status 405}
        "DELETE" {:status 204}
        "POST"
        (if-not (:id body) {:status 202}
          (let [args (get-in body [:params :arguments])
                name (get-in body [:params :name])
                result
                (case (:method body)
                  "initialize" {:protocolVersion (get-in body [:params :protocolVersion])
                                :capabilities {:tools {}}
                                :serverInfo {:name "fixture" :version "1"}}
                  "tools/list" {:tools (mapv tool (case path
                                                   "/read" reader-tools
                                                   "/write" writer-tools
                                                   "/search" ["web_search"]))}
                  "tools/call"
                  {:isError false
                   :structuredContent
                   (case name
                     "web_search" {:url "https://source.test/news"}
                     "get_commit" {:sha (if @pushed commit-sha base-sha)
                                   :files [{:filename "src/a.clj"}]}
                     "get_file_contents"
                     (if (= "src" (:path args))
                       {:entries [{:path "src/a.clj" :type "file" :size 4
                                   :sha (if @pushed "3e757656cf36eca53338e520d134963a44f793f8"
                                            "3367afdbbf91e638efe983616377c60477cc6612")}]}
                       {:type "file" :encoding "base64"
                        :content (.encodeToString (Base64/getEncoder)
                                                  (.getBytes (if @pushed "new\n" "old\n") "UTF-8"))})
                     "create_branch" {:ref (:branch args)}
                     "push_files" (do (reset! pushed true) {:sha commit-sha})
                     "create_pull_request" {:html_url "https://github.com/owner/repo/pull/12"})})]
            {:headers {"Mcp-Session-Id" "integration-session"}
             :body {:jsonrpc "2.0" :id (:id body) :result result}}))))))

(defn- check-transports [discovery]
  (let [requests (atom []) pushed (atom false)
        handler (fixture-handler requests pushed)]
    (support/with-http-server
      (fn [request]
        (let [response (handler request)]
          (if (and (= discovery :automatic)
                   (#{"/read" "/write"} (:path request))
                   (= "tools/list" (get-in request [:body :method])))
            (update-in response [:body :result :tools] into
                       (if (= "/read" (:path request))
                         [(assoc (tool "push_files")
                                 :annotations {:readOnlyHint true})
                          (tool "unknown_operation")]
                         [(tool "delete_repository")]))
            response)))
      (fn [base]
        (let [provider {:name "local" :url (str base "/v1") :model "chat"}
              request chat/request
              t {:name "github-pr" :kind "github-pr"
                 :users ["U1"] :adapters ["slack"]
                 :repositories ["owner/repo"]
                 :providers [{:name "local" :models ["chat"]}]
                 :base-branches ["main"] :allowed-paths ["src/"]
                 :write-server "writer"
                 :servers [(cond-> {:name "reader" :tools reader-tools}
                             (not= discovery :explicit) (dissoc :tools))]}
              config {:default-toolset "search"
                      :toolsets [{:name "search" :kind "search"
                                  :servers
                                  [(cond-> {:name "web" :tools ["web_search"]}
                                     (not= discovery :explicit)
                                     (dissoc :tools))]} t]
                      :servers [{:name "web" :url (str base "/search") :tools ["web_search"]}
                                {:name "reader" :url (str base "/read") :tools reader-tools
                                 :github-api-token "ancestry-secret"}
                                {:name "writer" :url (str base "/write") :tools writer-tools
                                 :headers {"Authorization" "Bearer write-secret"}}
                                {:name "offline" :url "http://127.0.0.1:9/mcp"
                                 :tools ["unused"]}]}
              config (if (= discovery :automatic)
                       (update config :servers
                               #(mapv (fn [s]
                                        (if (= "offline" (:name s)) s
                                          (dissoc s :tools))) %))
                       config)]
          (with-redefs [api/config (constantly {:providers [provider]})
                        mcp/config (constantly config)
                        context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                        client/get (fn [url _] {:status 200 :body {:sha (last (string/split url #"/"))
                                                                  :parents [{:sha base-sha}]}})]
            (fact
              (:value (chat/run-command request "ai --toolset search Search current news"))
              => "Source: https://source.test/news"
              (some #(= "/read" (:path %)) @requests) => nil)
            (let [names (:value (chat/run-command request "ai tools"))]
              (fact (some #{"`web/web_search` — web_search"} names)
                    => "`web/web_search` — web_search"
                    (some #{"`reader/get_commit` — get_commit"} names)
                    => "`reader/get_commit` — get_commit"
                    (some #(re-find #"push_files|unknown_operation" %) names)
                    => nil
                    (some #(= "/write" (:path %)) @requests) => nil))
            (let [result (chat/run-command request "ai --toolset github-pr Fix src/a.clj")
                  id (get-in result [:data :proposal])
                  p (proposals/review! id actor)]
              (fact (string? id) => true
                    (some #(= "/write" (:path %)) @requests) => nil)
              (doseq [page (range 1 (inc (proposals/page-count p)))]
                (chat/run-command request (str "ai proposal " id " " page)))
              (fact
                (get-in (chat/run-command request (str "ai approve " id))
                        [:data :status]) => :succeeded
                (contains? (chat/run-command request (str "ai approve " id)) :error) => true
                (count (filter #(= "push_files" (get-in % [:body :params :name])) @requests)) => 1
                (some #(= "delete_repository"
                          (get-in % [:body :params :name])) @requests) => nil
                (get-in (last (filter #(= "create_pull_request" (get-in % [:body :params :name])) @requests))
                        [:body :params :arguments :draft]) => true
                (every? #(not (re-find #"write-secret|ancestry-secret"
                                     (json/generate-string (:body %))))
                        (filter #(= "/v1/chat/completions" (:path %))
                                @requests))
                => true
                (some #(re-find #"push_files|create_|delete_repository|unknown_"
                                (json/generate-string
                                  (get-in % [:body :tools])))
                      (filter #(= "/v1/chat/completions" (:path %)) @requests))
                => nil))))))))

(fact "real HTTP/MCP transports preserve discovery, policy and approved writes"
  (doseq [discovery [:explicit :inherit :automatic]]
    (check-transports discovery)))

(fact "GitHub tools are discoverable and executable across channels and threads"
  (let [requests (atom [])
        discord (fn [room]
                  {:user {:id "U1" :username "alice"}
                   :chat-source
                   {:adapter :discord :uuid "discord-prod" :room room
                    :raw-event {:author {:id "U1"} :channel-id room
                                :id "M1"}}})
        other-slack (-> chat/request
                        (assoc-in [:chat-source :room] "other")
                        (assoc-in [:chat-source :raw-event :channel] "C2"))]
    (support/with-http-server
      (fixture-handler requests (atom false))
      (fn [base]
        (let [provider {:name "local" :url (str base "/v1") :model "chat"}
              toolset {:name "repo" :kind "github-read" :users ["U1"]
                       :adapters ["slack" "discord"]
                       :repositories ["owner/repo"]
                       :providers [{:name "local" :models ["chat"]}]
                       :servers [{:name "reader" :tools ["get_commit"]}]}]
          (doseq [legacy? [false true]
                  request [(discord "parent") (discord "thread")
                           chat/request other-slack]]
            (let [t (cond-> toolset
                      legacy? (assoc :conversations ["discord-prod/parent"]))
                  config {:toolsets [t]
                          :servers [{:name "reader" :url (str base "/read")
                                     :tools ["get_commit"]}]}]
              (reset! requests [])
              (with-redefs
                [api/config (constantly {:providers [provider]})
                 mcp/config (constantly config)
                 context/messages (fn [_ prompt]
                                    [{:role "user" :content prompt}])]
                (fact
                  (:value (chat/run-command request "ai toolsets")) => ["repo"]
                  (some #{"`reader/get_commit` — get_commit"}
                        (:value (chat/run-command request "ai tools")))
                  => "`reader/get_commit` — get_commit"
                  (:value (chat/run-command request
                                           "ai --toolset repo Read owner/repo"))
                  => "Commit loaded."
                  (count (filter #(= "get_commit"
                                     (get-in % [:body :params :name]))
                                 @requests)) => 1)))))))))
