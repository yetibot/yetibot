(ns yetibot.test.scripts.ai-live-smoke
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [midje.sweet :refer :all]
            [taoensso.timbre :as log]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-native-tools :as native]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.test.support.ai-execution :as support]
            [yetibot.util.ai-execution :as execution]
            [yetibot.core.util.agent-execution :as origin])
  (:import [java.io ByteArrayInputStream File]
           [java.net SocketTimeoutException]))

(def providers
  [{:name "grok" :url "https://api.x.ai/v1" :api "responses"
    :key "fixture-key-only" :model "grok-4.7"}
   {:name "cloudflare" :api "chat-completions"
    :url (str "https://api.cloudflare.com/client/v4/accounts/"
              "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/ai/v1")
    :key "fixture-key-only" :model "@cf/zai-org/glm-4.7-flash"}])

(defn smoke-var [name]
  (ns-resolve 'yetibot.scripts.ai-live-smoke name))

(defn read-state [file]
  (json/parse-string (slurp file) true))

(defn fresh-ledger [state f]
  (let [file (File/createTempFile "yetibot-smoke-test-" ".json")
        names ["yetibot.live-smoke.enabled" "yetibot.live-smoke.budget"]
        previous (mapv #(System/getProperty %) names)
        old-log (.getRawRoot #'log/*config*)]
    (try
      (spit file (json/generate-string state))
      (System/setProperty (first names) "true")
      (System/setProperty (second names) (.getAbsolutePath file))
      (binding [*command-line-args* nil]
        (load-file "scripts/ai_live_smoke.clj"))
      (with-redefs [ai/config (constantly {:providers providers})
                    http/get (fn [& _]
                               (throw (ex-info "Unexpected GET." {})))]
        (f file))
      (finally
        (doseq [[name value] (map vector names previous)]
          (if value (System/setProperty name value)
              (System/clearProperty name)))
        (alter-var-root #'log/*config* (constantly old-log))
        (.delete file)))))

(defn run-case [provider case-name]
  (let [result (atom nil)]
    (with-out-str
      (reset! result ((smoke-var 'run!) provider case-name)))
    @result))

(defn response [options output]
  (let [body {:id "smoke-response" :status "completed" :output output}]
    {:status 200
     :body (if (= :stream (:as options))
             (ByteArrayInputStream.
               (.getBytes
                 (str "event: response.completed\ndata: "
                      (json/generate-string
                        {:type "response.completed" :response body})
                      "\n\n") "UTF-8"))
             body)}))

(def answer
  [{:type "message" :role "assistant"
    :content [{:type "output_text" :text "0.3"}]}])

(fact "standalone callback cases are known not sent before credentials or ledger mutation"
  (fresh-ledger
    {:cap 2 :used 0 :attempts []}
    (fn [file]
      (let [before (slurp file) posts (atom 0)]
        (with-redefs [ai/config (fn [] (throw (AssertionError. "No credential/config lookup")))
                      http/post (fn [& _] (swap! posts inc))]
          (doseq [event [nil {:user {:id "operator-smoke"}
                             :chat-source {:adapter :discord :uuid "forged"}}]
                  case-name ["sum" "budget" "aliases" "unknown" "random-model"]]
            (binding [execution/*event* event]
              (:known-not-sent (run-case "grok" case-name)) => true
              (:unsupported-live-callback (run-case "grok" case-name)) => true))
          (let [request (execution/family 0 2048)]
            (execution/with-request* request
              #(:known-not-sent (run-case "grok" "budget"))) => true)
          @posts => 0
          (slurp file) => before)))))

(fact "registered offline native callback retains two POST terminal budget proof"
  (let [provider (first providers) posts (atom []) callbacks (atom [])
        native-tools native/tools
        t {:name "offline-smoke" :kind "search" :servers [] :native-tools ["util"]
           :users ["U1"] :adapters ["slack"]
           :providers [{:name "grok" :models ["grok-4.7"]}]}]
    (with-redefs [ai/config (constantly {:providers [provider]})
                  mcp/config (constantly {:servers [] :toolsets [t]})
                  native/tools
                  (fn [& args]
                    (mapv (fn [tool]
                            (let [callback (:host-call tool)]
                              (assoc tool :host-call
                                     (fn [input]
                                       (let [result (callback input)]
                                         (swap! callbacks conj (get-in result [:structuredContent :result]))
                                         result)))))
                          (apply native-tools args)))
                  http/post
                  (fn [_ options]
                    (let [body (:form-params options)]
                      (swap! posts conj body)
                      (response options
                        (if (seq (:tools body))
                          [{:type "function_call" :id "fc-smoke" :call_id "call-smoke"
                            :name (:name (first (:tools body)))
                            :arguments (json/generate-string {:operation "sum" :input_json "[0.1,0.2]"})}]
                          answer))))]
      (support/in-registered-event support/request "ai offline callback"
        (fn [event]
          ;; Three physical admissions (parent, host callback, terminal parent),
          ;; independent of the single tool-attempt presentation budget.
          (execution/with-source* {:provider provider :model "grok-4.7"
                                   :toolset "offline-smoke" :max-tool-calls 3}
            #(let [result (tools/complete provider "grok-4.7" []
                                          {:actor (policy/actor event) :toolset "offline-smoke"
                                           :max-tool-calls 1 :max-output-tokens 2048})]
               (:result/value result) => "0.3"
               (:admitted (execution/admissions execution/*request*)) => 3)))))
    @callbacks => [0.3M]
    (count @posts) => 2
    (:tools (second @posts)) => []
    (contains? (second @posts) :tool_choice) => false
    (mapv :max_output_tokens @posts) => [2048 2048]))

(fact "a possible paid timeout durably stops subsequent requests"
  (fresh-ledger
    {:cap 2 :used 0 :attempts []}
    (fn [file]
      (let [posts (atom 0)]
        (with-redefs
          [http/post (fn [& _]
                       (swap! posts inc)
                       (throw (SocketTimeoutException. "Synthetic timeout.")))]
          (:uncertain-outcome (run-case "grok" "greeting")) => true
          (:stopped (read-state file)) => true
          (:pending (read-state file)) => true
          (:used (read-state file)) => 1
          (:passed (run-case "grok" "greeting")) => false
          @posts => 1)))))

(fact "a pending request left by a crashed process cannot be resumed"
  (fresh-ledger
    {:cap 2 :used 1 :attempts [{:case "prior"}] :pending true}
    (fn [file]
      (let [posts (atom 0)]
        (with-redefs [http/post (fn [& _] (swap! posts inc))]
          (:passed (run-case "grok" "greeting")) => false
          (:stopped (read-state file)) => true
          (:used (read-state file)) => 1
          @posts => 0)))))

(fact "reasoning-only length termination is a failed, but known, response"
  (fresh-ledger
    {:cap 1 :used 0 :attempts []}
    (fn [file]
      (with-redefs
        [http/post
         (fn [& _]
           {:status 200
            :body {:choices
                   [{:finish_reason "length"
                     :message {:content ""
                               :reasoning_content "Synthetic."}}]}})]
        (let [result (run-case "cloudflare" "greeting")]
          (:passed result) => false
          (:uncertain-outcome result) => false
          (:response-shapes result)
          => [{:finish :length :visible-text? false :reasoning? true}]
          (:pending (read-state file)) => false
          (:stopped (read-state file)) => nil)))))

(fact "command examples use the real parser, not a single-line substring test"
  (fresh-ledger
    {:cap 1 :used 0 :attempts []}
    (fn [_]
      (let [check (smoke-var 'command-check)
            model "@cf/test/chat"]
        (:valid-command
          (check (str "```text\n!ai --model " model "\n"
                      "--provider cloudflare Say hello.\n```") model))
        => true
        (:valid-command
          (check (str "!ai --provider cloudflare --model " model) model))
        => false
        (:valid-command
          (check (str "!ai --provider cloudflare --random Say hello.") model))
        => false))))
