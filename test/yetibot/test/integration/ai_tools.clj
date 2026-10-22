(ns yetibot.test.integration.ai-tools
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as api]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai :as commands]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.test.support.mcp :as support])
  (:import [java.util Base64]))

(def base-sha (apply str (repeat 40 "a")))
(def commit-sha (apply str (repeat 40 "b")))
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
            done? (= "tool" (:role (last (:messages body))))
            name (some #(let [n (get-in % [:function :name])]
                          (when (string/ends-with? n (if search? "web_search" "stage_changes")) n))
                       (:tools body))
            arguments (if search? {"query" "news"}
                        {"owner" "owner" "repo" "repo" "base" "main"
                         "title" "Fix a" "body" "A focused fix"
                         "files" [{"path" "src/a.clj" "content" "new\n"}]})]
        {:body {:choices [{:message (if done?
                                      {:role "assistant" :content "Source: https://source.test/news"}
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
                                   :parents [{:sha base-sha}]
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

(fact "real OpenAI HTTP and MCP transports connect chat, policy, staging and approval"
  (let [requests (atom []) pushed (atom false)]
    (support/with-http-server
      (fixture-handler requests pushed)
      (fn [base]
        (let [provider {:name "local" :url (str base "/v1") :model "chat"}
              request {:chat-source {:adapter :slack :uuid "workspace"
                                     :raw-event {:user "U1" :channel "C1" :ts "1"}}}
              t {:name "github-pr" :kind "github-pr"
                 :users ["U1"] :adapters ["slack"]
                 :conversations ["workspace/C1"] :repositories ["owner/repo"]
                 :providers [{:name "local" :models ["chat"]}]
                 :base-branches ["main"] :allowed-paths ["src/"]
                 :write-server "writer"
                 :servers [{:name "reader" :tools reader-tools}]}
              config {:default-toolset "search"
                      :toolsets [{:name "search" :kind "search"
                                  :servers [{:name "web" :tools ["web_search"]}]} t]
                      :servers [{:name "web" :url (str base "/search") :tools ["web_search"]}
                                {:name "reader" :url (str base "/read") :tools reader-tools}
                                {:name "writer" :url (str base "/write") :tools writer-tools
                                 :headers {"Authorization" "Bearer write-secret"}}
                                {:name "offline" :url "http://127.0.0.1:9/mcp" :tools ["unused"]}]}]
          (with-redefs [api/config (constantly {:providers [provider]})
                        mcp/config (constantly config)]
            (fact
              (:result/value (commands/prompt-cmd
                               (assoc request :match "--toolset search Search current news")))
              => "Source: https://source.test/news"
              (some #(= "/read" (:path %)) @requests) => nil)
            (let [result (commands/prompt-cmd
                           (assoc request :match "--toolset github-pr Fix src/a.clj"))
                  id (get-in result [:result/data :proposal])
                  p (proposals/review! id (policy/actor request))]
              (fact (string? id) => true
                    (some #(= "/write" (:path %)) @requests) => nil)
              (doseq [page (range 1 (inc (proposals/page-count p)))]
                (commands/proposal-cmd (assoc request :match ["proposal" id (str page)])))
              (fact
                (get-in (commands/approve-cmd (assoc request :match ["approve" id]))
                        [:result/data :status]) => :succeeded
                (contains? (commands/approve-cmd (assoc request :match ["approve" id]))
                           :result/error) => true
                (count (filter #(= "push_files" (get-in % [:body :params :name])) @requests)) => 1
                (get-in (last (filter #(= "create_pull_request" (get-in % [:body :params :name])) @requests))
                        [:body :params :arguments :draft]) => true
                (every? #(not (string/includes? (json/generate-string (:body %)) "write-secret"))
                        (filter #(= "/v1/chat/completions" (:path %)) @requests)) => true))))))))
