(ns yetibot.test.integration.opencode-zen
  (:require [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.opencode :as opencode]
            [yetibot.commands.zen]
            [yetibot.core.commands.collections]
            [yetibot.core.util.http :as core-http]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-provider-catalog :as catalog]
            [yetibot.test.support.ai-execution :as execution]))

(def zen {:name "zen" :url "https://opencode.ai/inference/openai/v1"
          :key "zen-fixture-secret" :model "fixture-chat"})
(def other {:name "other" :url "https://other.example/v1" :model "other"})
(def settings {:providers [other zen] :default-provider "other"})
(def records [{:id "fixture-chat" :type "chat"}
              {:id "new-model-arrived-today" :type "chat"}
              {:id "other" :type "chat"}])
(defn response [] {:status 200 :body {:object "list" :data records}})

(facts "Zen model discovery uses the catalog, not the chat models endpoint"
  (with-redefs [http/get
                (fn [url options]
                  (fact url => "https://opencode.ai/inference/v1/models")
                  (fact (get-in options [:headers "Authorization"])
                        => "Bearer zen-fixture-secret")
                  (fact (:redirect-strategy options) => :none)
                  (fact ((:retry-handler options) nil nil nil) => false)
                  (response))]
    (let [models (ai/models zen)]
      models => ["fixture-chat" "new-model-arrived-today" "other"]
      (:complete? (meta models)) => true))
  ;; Legacy base URLs still work without redirecting paid requests.
  (:url (opencode/catalog-provider
          (assoc zen :url "https://opencode.ai/zen/v1/")))
  => "https://opencode.ai/zen/v1/")

(fact "lookalike hosts, insecure URLs and unrelated APIs are never rewritten"
  (doseq [url ["https://opencode.ai.evil.example/inference/openai/v1"
               "http://opencode.ai/inference/openai/v1"
               "https://opencode.ai:444/inference/openai/v1"
               "https://opencode.ai/unrelated/v1"
               "https://other.example/v1"]]
    (fact (opencode/catalog-provider (assoc zen :url url))
          => (assoc zen :url url))))

(fact "Zen discovery is public but prompts fail before POST without a key"
  (let [posts (atom 0)
        provider (dissoc zen :key)]
    (with-redefs [http/get (fn [& _] (response))
                  http/post (fn [& _] (swap! posts inc))]
      (count (ai/models provider)) => 3
      (try (ai/chat provider "fixture-chat" [] {}) nil
           (catch Exception e (.getMessage e)))
      => (str "OpenCode Zen requires an API key. Configure the "
              "provider's key or key-file; model discovery is public.")
      @posts => 0)))

(fact "the config placeholder is never sent as a credential or used for POST"
  (let [provider (assoc zen :key opencode/api-key-placeholder)
        calls (atom 0)]
    (with-redefs [http/get (fn [_ options]
                            (fact (get-in options [:headers "Authorization"])
                                  => nil)
                            (response))
                  http/post (fn [& _] (swap! calls inc))]
      (count (ai/models provider)) => 3
      (try (ai/chat provider "fixture-chat" [] {}) nil
           (catch Exception _ :blocked)) => :blocked
      @calls => 0)))

(fact "bare zen shows usage without GitHub or inference requests"
  (with-redefs [core-http/fetch (fn [& _]
                                 (throw (AssertionError. "No GitHub wisdom")))
                ai/complete (fn [& _] (throw (AssertionError. "No inference")))]
    (let [result (execution/run-command execution/request "zen")]
      (string/includes? (str (:value result)) "zen models") => true
      (string/includes? (str (:value result)) "--model") => true
      (get-in result [:data :isError]) => nil)))

(fact "zen models discovers every ID and preserves pipeline selection"
  (with-redefs [ai/config (constantly settings)
                http/get (fn [& _] (response))
                ai/complete (fn [& _] (throw (AssertionError. "inference")))
                clojure.core/rand-int (constantly 0)]
    (let [listing (execution/run-command execution/request "zen models")
          picked (execution/run-command execution/request
                   "zen models | random")
          shown (execution/run-command execution/request
                  "zen models | random | data show")]
      (:value listing)
      => ["fixture-chat" "new-model-arrived-today" "other"]
      (:data-collection listing) => (mapv #(hash-map :id %) (:value listing))
      (:data picked) => {:id "fixture-chat"}
      (string/includes? (str (:value shown)) "fixture-chat") => true)))

(fact "invalid discovery options never turn into paid prompts"
  (let [requests (atom 0)]
    (with-redefs [ai/config (constantly settings)
                  mcp/config (constantly {:servers []})
                  http/get (fn [& _] (swap! requests inc) (response))
                  ai/complete (fn [& _] (swap! requests inc))]
      (let [result (execution/run-command execution/request
                     "zen models --typo | echo must-not-run")]
        @requests => 0
        (get-in result [:data :isError]) => true
        (string/includes? (str (:value result)) "must-not-run") => false))))

(fact "zen prompts pin Zen without implicit history or child tools"
  (let [calls (atom [])]
    (with-redefs [ai/config (constantly settings)
                  mcp/config (constantly {:servers []})
                  context/messages
                  (fn [& _] (throw (AssertionError. "implicit history")))
                  ai/complete
                  (fn [provider model messages options]
                    (swap! calls conj [provider model messages options])
                    {:result/value "Zen answer"})]
      (let [result (execution/run-command execution/request
                     "zen --model new-model-arrived-today Say hello")
            [provider model messages options] (first @calls)]
        (:value result) => "Zen answer"
        (:name provider) => "zen"
        model => "new-model-arrived-today"
        messages => [{:role "user" :content "Say hello"}]
        (:tools options) => []
        (:native-tools provider) => []
        (:max-output-tokens options) => 2048
        (count @calls) => 1))))

(fact "ai supports Zen explicitly and through complete automatic discovery"
  (with-redefs [ai/config (constantly settings)
                http/get (fn [url & _]
                           (if (string/starts-with? url "https://opencode.ai/")
                             (response)
                             {:status 200 :body {:object "list"
                                                 :data [{:id "other"}]}}))]
    (let [cache (catalog/new-cache (constantly 1000) (fn [job] (job))
                                   (constantly settings))]
      (catalog/warm! cache settings)
      (catalog/infer-provider cache settings "new-model-arrived-today")
      => "zen")
    (with-redefs [mcp/config (constantly {:servers []})
                  context/messages (fn [_ prompt]
                                     [{:role "user" :content prompt}])
                  ai/complete
                  (fn [provider model _]
                    (fact (:name provider) => "zen")
                    (fact model => "fixture-chat")
                    {:result/value "Explicit Zen answer"})]
      (:value (execution/run-command execution/request
                "ai --provider zen --no-tools Say hello"))
      => "Explicit Zen answer")))

(fact "Zen failure is sanitized, never retried, and stops downstream commands"
  (let [calls (atom 0)]
    (with-redefs [ai/config (constantly settings)
                  mcp/config (constantly {:servers []})
                  ai/complete
                  (fn [& _]
                    (swap! calls inc)
                    (throw (ex-info "zen-fixture-secret private input" {})))]
      (let [result (execution/run-command execution/request
                     "zen private input | echo must-not-run")]
        @calls => 1
        (get-in result [:data :isError]) => true
        (get-in result [:data :unknownOutcome]) => true
        (string/includes? (str (:value result)) "must-not-run") => false
        (string/includes? (str result) "zen-fixture-secret") => false
        (string/includes? (str (:value result)) "private input") => false))))
