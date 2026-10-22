(ns yetibot.test.models.ai-tools
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.mcp :as support]))

(def provider {:name "local" :model "chat" :url "http://localhost/v1"})
(def config {:servers [{:name "search" :url "https://search.test/mcp" :tools ["web_search"]}]})
(def tool (assoc support/search-tool :session {:server (first (:servers config))}))
(def messages [{:role "user" :content "thread root"} {:role "assistant" :content "old reply"}
               {:role "user" :content "What's new today?"}])

(defn error-message [f] (try (f) (catch Exception e (.getMessage e))))
(defn answer [text] {:choices [{:message {:role "assistant" :content text}}]})
(defn tool-call [name id arguments]
  {:id id :type "function" :function {:name name :arguments arguments}})
(defn tool-response [calls] {:choices [{:message {:role "assistant" :content nil :tool_calls calls}}]})
(defn first-name [options] (get-in options [:tools 0 :function :name]))

(fact "without MCP settings, the existing completion path is unchanged"
  (with-redefs [mcp/config (constantly nil)
                ai/complete (fn [p m msgs] [p m msgs])]
    (tools/complete provider "chat" messages) => [provider "chat" messages]))

(fact "the model can choose not to call any tools"
  (let [requests (atom []) called? (atom false)]
    (with-redefs [mcp/config (constantly config)
                  mcp/with-tools (fn [_ f] (f [tool]))
                  mcp/call-tool (fn [& _] (reset! called? true))
                  ai/chat (fn [p m msgs options]
                            (swap! requests conj [p m msgs options]) (answer "No search needed."))]
      (:result/value (tools/complete provider "chat" messages)) => "No search needed."
      @called? => false
      (get-in @requests [0 3 :tool_choice]) => "auto"
      (get-in @requests [0 2 0 :role]) => "system"
      (vec (rest (get-in @requests [0 2]))) => messages)))

(fact "web search results return as tool messages with source links and the original call ID"
  (let [requests (atom []) executions (atom [])]
    (with-redefs [mcp/config (constantly config)
                  mcp/with-tools (fn [_ f] (f [tool]))
                  mcp/call-tool (fn [session name arguments]
                                  (swap! executions conj [session name arguments])
                                  {:content [{:type "text" :text "Source: https://source.test/news"}]
                                   :structuredContent {:url "https://source.test/news"} :isError false})
                  ai/chat (fn [_ _ msgs options]
                            (swap! requests conj {:messages msgs :options options})
                            (if (= 1 (count @requests))
                              (tool-response [(tool-call (first-name options) "search-1" "{\"query\":\"latest news\"}")])
                              (answer "Today's news: https://source.test/news")))]
      (let [result (tools/complete provider "chat" messages)
            reply (last (:messages (second @requests)))]
        (fact
          (:result/value result) => "Today's news: https://source.test/news"
          (get-in result [:result/data :mcp :tool-calls]) => 1
          (mapv #(subvec % 1) @executions) => [["web_search" {"query" "latest news"}]]
          (select-keys reply [:role :tool_call_id]) => {:role "tool" :tool_call_id "search-1"}
          (get-in (json/parse-string (:content reply) true) [:structuredContent :url])
          => "https://source.test/news"
          (get-in @requests [0 :options :tools 0 :function :parameters]) => (:inputSchema support/search-tool)
          (boolean (re-find #"untrusted data" (get-in @requests [0 :messages 0 :content]))) => true)))))

(fact "tools with the same name on different servers get distinct model names and correct routing"
  (let [other (assoc-in tool [:session :server :name] "other")
        names (atom []) calls (atom [])]
    (with-redefs [mcp/config (constantly config)
                  mcp/with-tools (fn [_ f] (f [tool other]))
                  mcp/call-tool (fn [session _ arguments]
                                  (swap! calls conj [(get-in session [:server :name]) arguments])
                                  {:content [] :isError false})
                  ai/chat (fn [_ _ _ options]
                            (if (empty? @names)
                              (let [tool-names (mapv #(get-in % [:function :name]) (:tools options))]
                                (reset! names tool-names)
                                (tool-response (mapv #(tool-call %1 (str "id-" %2) "{\"query\":\"news\"}")
                                                     tool-names (range))))
                              (answer "Done.")))]
      (:result/value (tools/complete provider "chat" messages)) => "Done."
      (count (distinct @names)) => 2
      (every? #(boolean (re-matches #"[a-zA-Z0-9_-]{1,64}" %)) @names) => true
      @calls => [["search" {"query" "news"}] ["other" {"query" "news"}]])))

(facts "invalid calls reject the entire response before any external action"
  (doseq [kind [:unknown :bad-json :array-arguments :duplicate-id :duplicate-action :over-budget :wrong-type]]
    (let [calls (atom [])]
      (with-redefs [mcp/config (constantly (assoc config :max-tool-calls (if (= kind :over-budget) 1 6)))
                    mcp/with-tools (fn [_ f] (f [tool]))
                    mcp/call-tool (fn [& args] (swap! calls conj args))
                    ai/chat (fn [_ _ _ options]
                              (let [name (first-name options)
                                    valid (tool-call name "one" "{}")]
                                (tool-response
                                  (case kind
                                    :unknown [valid (tool-call "not_allowed" "two" "{}")]
                                    :bad-json [(tool-call name "one" "not json")]
                                    :array-arguments [(tool-call name "one" "[]")]
                                    :duplicate-id [valid valid]
                                    :duplicate-action [valid (assoc valid :id "two")]
                                    :over-budget [valid (tool-call name "two" "{\"query\":\"second\"}")]
                                    :wrong-type [(assoc valid :type "other")]))))]
        (fact (string? (error-message #(tools/complete provider "chat" messages))) => true)
        (fact @calls => [])))))

(fact "after the configured call budget, the final request disables tools"
  (let [choices (atom []) executions (atom 0)]
    (with-redefs [mcp/config (constantly (assoc config :max-tool-calls "2"))
                  mcp/with-tools (fn [_ f] (f [tool]))
                  mcp/call-tool (fn [& _] (swap! executions inc) {:content []})
                  ai/chat (fn [_ _ _ options]
                            (swap! choices conj (:tool_choice options))
                            (if (= "none" (:tool_choice options)) (answer "Finished with sources.")
                              (tool-response [(tool-call (first-name options) (str "id-" (count @choices))
                                                        (json/generate-string {:query (str "query " (count @choices))}))])))]
      (:result/value (tools/complete provider "chat" messages)) => "Finished with sources."
      @executions => 2
      @choices => ["auto" "auto" "none"])))

(fact "a model that ignores the budget cannot run additional calls"
  (let [executions (atom 0) requests (atom 0)]
    (with-redefs [mcp/config (constantly (assoc config :max-tool-calls 1))
                  mcp/with-tools (fn [_ f] (f [tool]))
                  mcp/call-tool (fn [& _] (swap! executions inc) {:content []})
                  ai/chat (fn [_ _ _ options]
                            (tool-response [(tool-call (first-name options) (str "id-" (swap! requests inc)) "{}")]))]
      (string? (error-message #(tools/complete provider "chat" messages))) => true
      @executions => 1)))

(fact "a failed or uncertain tool call is never replayed under a new call ID"
  (let [executions (atom 0) requests (atom 0)]
    (with-redefs [mcp/config (constantly config)
                  mcp/with-tools (fn [_ f] (f [tool]))
                  mcp/call-tool (fn [& _] (swap! executions inc) {:isError true :content []})
                  ai/chat (fn [_ _ _ options]
                            (tool-response [(tool-call (first-name options) (str "id-" (swap! requests inc)) "{}")]))]
      (error-message #(tools/complete provider "chat" messages))
      => "The AI provider tried to repeat a tool call. No tools from that response were run."
      @executions => 1)))

(fact "an unknown outcome halts the rest of the batch and disables further tools"
  (let [executions (atom 0) requests (atom [])]
    (with-redefs [mcp/config (constantly config)
                  mcp/with-tools (fn [_ f] (f [tool]))
                  mcp/call-tool (fn [& _] (swap! executions inc)
                                  {:isError true :unknownOutcome true :content [{:type "text" :text "Timed out; outcome unknown."}]})
                  ai/chat (fn [_ _ msgs options]
                            (swap! requests conj {:messages msgs :options options})
                            (if (= 1 (count @requests))
                              (tool-response [(tool-call (first-name options) "one" "{}")
                                              (tool-call (first-name options) "two" "{\"query\":\"other\"}")])
                              (answer "The tool timed out. I could not confirm the result.")))]
      (get-in (tools/complete provider "chat" messages) [:result/data :mcp :tool-calls]) => 1
      @executions => 1
      (get-in @requests [1 :options :tool_choice]) => "none"
      (:tool_call_id (last (get-in @requests [1 :messages]))) => "two"
      (string/includes? (:content (last (get-in @requests [1 :messages]))) "not run") => true)))

(fact "unsupported tool schemas fail before sending anything to the model"
  (let [sent? (atom false)]
    (with-redefs [mcp/config (constantly config)
                  mcp/with-tools (fn [_ f] (f [(assoc tool :inputSchema {:type "array"})]))
                  ai/chat (fn [& _] (reset! sent? true))]
      (error-message #(tools/complete provider "chat" messages))
      => "An MCP tool returned an unsupported input schema."
      @sent? => false)))

(fact "tool output is bounded, binary content is omitted and configured keys are redacted"
  (let [captured (atom nil) requests (atom 0)
        config (assoc-in config [:servers 0 :headers] {"Authorization" "Bearer private-token"})]
    (with-redefs [mcp/config (constantly config)
                  mcp/with-tools (fn [_ f] (f [tool]))
                  mcp/call-tool (fn [& _] {:content [{:type "text" :text (str "private-token " (apply str (repeat 30000 "x")))}
                                                    {:type "image" :data "binary-secret"}
                                                    {:type "resource" :resource {:uri "image://one" :blob "binary-secret"}}]})
                  ai/chat (fn [_ _ msgs options]
                            (if (= 1 (swap! requests inc))
                              (tool-response [(tool-call (first-name options) "one" "{}")])
                              (do (reset! captured (:content (last msgs))) (answer "private-token"))))]
      (:result/value (tools/complete provider "chat" messages)) => "[redacted]"
      (string/includes? @captured "private-token") => false
      (string/includes? @captured "binary-secret") => false
      (< (count @captured) 20100) => true
      (string/includes? @captured "shortened") => true)))

(facts "tool discovery commands list only configured allowed tools"
  (with-redefs [mcp/config (constantly config)
                mcp/with-tools (fn [settings f]
                                 (fact (mapv :name (:servers settings)) => ["search"])
                                 (f [tool]))]
    (tools/server-names) => ["search"]
    (tools/tool-names nil) => ["search/web_search"]
    (tools/tool-names "search") => ["search/web_search"]
    (error-message #(tools/tool-names "missing"))
    => "Unknown MCP server. Use ai servers to list configured servers.")
  (with-redefs [mcp/config (constantly nil)]
    (tools/server-names) => []
    (tools/tool-names nil) => "No MCP servers configured."))
