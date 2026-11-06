(ns yetibot.test.integration.ai-usage-capture
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.clef :as clef]
            [yetibot.api.cloudflare :as cf]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.openai :as openai]
            [yetibot.core.db.ai-usage :as store]
            [yetibot.core.util.ai-usage :as usage]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.test.support.ai-usage :refer [with-ledger]])
  (:import [java.io ByteArrayInputStream]))

(def provider {:name "fixture" :url "https://provider.invalid/v1"
               :model "one" :key "PRIVATE-KEY"})
(def body {:choices [{:message {:content "PRIVATE-ANSWER"}}]
           :usage {:prompt_tokens 3 :completion_tokens 2 :cost 12}})
(def account (apply str (repeat 32 "a")))

(fact "chat captures before parsing and unknown hosts cannot assert USD"
  (with-ledger
    (fn [events]
      (with-redefs [http/post
                    (fn [& _]
                      (mapv :state @events) => ["pending"]
                      {:status 200 :body body})
                    http/get (fn [& _] {:status 200 :body {:data [{:id "one"}]}})]
        (ai/chat provider "one" [{:role "user" :content "PRIVATE-PROMPT"}] {})
        => body
        (ai/models provider) => ["one"])
      (count @events) => 1
      (get-in @events [0 :model-usage])
      => [{:model "one" :requests 1 :input_tokens 3 :output_tokens 2}]
      (string/includes? (pr-str @events) "PRIVATE") => false)))

(fact "Responses streams capture final usage, failures remain honest unknowns"
  (with-ledger
    (fn [events]
      (let [p (assoc provider :name "grok" :url "https://api.x.ai/v1"
                             :api "responses")
            result {:status "completed"
                    :output [{:type "message"
                              :content [{:type "output_text" :text "answer"}]}]
                    :usage {:input_tokens 4 :output_tokens 2
                            :cost_in_usd_ticks 10000000}}
            stream (str "data: " (json/generate-string
                                   {:type "response.completed" :response result})
                        "\n\ndata: [DONE]\n\n")]
        (with-redefs [http/post
                      (fn [& _]
                        {:status 200 :headers {"content-type" "text/event-stream"}
                         :body (ByteArrayInputStream. (.getBytes stream "UTF-8"))})]
          (ai/chat p "one" [] {:on-progress (fn [& _])}) => result)
        (get-in @events [0 :model-usage 0 :usd]) => 0.001M
        (with-redefs [http/post
                      (fn [& _]
                        {:status 200 :headers {"content-type" "text/event-stream"}
                         :body (ByteArrayInputStream. (.getBytes "data: broken\n\n"))})]
          (try (ai/chat p "one" [] {:on-progress (fn [& _])})
               (catch Exception _ :failed)) => :failed)
        (get-in @events [1 :state]) => "unknown"
        (get-in @events [1 :model-usage 0 :usd]) => nil))))

(fact "normalization errors do not erase an already charged provider receipt"
  (with-ledger
    (fn [events]
      (let [p (assoc provider :url (str "https://api.cloudflare.com/client/v4/accounts/"
                                       account "/ai/v1"))
            model "@cf/google/gemma-4-26b-a4b-it"]
        (with-redefs [http/post
                      (fn [& _]
                        {:status 200 :body (assoc-in body [:choices 0 :message :content]
                                                    "<|tool_call bad")})]
          (try (ai/chat p model [] {}) (catch Exception _ :rejected)) => :rejected)
        (get-in @events [0 :state]) => "completed"
        (get-in @events [0 :model-usage 0 :input_tokens]) => 3))))

(fact "legacy OpenAI and both Workers structured paths use the same boundary"
  (with-ledger
    (fn [events]
      (with-redefs [openai/config {:key "PRIVATE-KEY"}
                    http/post (fn [& _] {:status 200 :body body})]
        (:body (openai/openai-completions [{:role "user" :content "PRIVATE"}]))
        => body)
      (with-redefs [cf/request!
                    (fn [& _]
                      {:success true :usage {:prompt_tokens 8}
                       :result {:model "clef"
                                :answers {:q {:type "noul" :noul 0.5}}}})]
        (:model (cf/infer! {:account-id account} "PRIVATE-KEY" "clef"
                           {:questions {:q {:type "noul"}}})) => "clef")
      (with-redefs [clef/credential! (constantly "PRIVATE-KEY")
                    http/post
                    (fn [& _]
                      {:status 200 :body
                       {:success true :usage {:prompt_tokens 9}
                        :result {:model "clef"
                                 :answers {:intent {:type "choice" :choice "answer"
                                                    :confidence 0.98
                                                    :probabilities {:answer 0.98
                                                                    :clarify 0.01
                                                                    :code_change 0.01}}
                                           :explicit_change {:type "noul" :noul 0.1}}}}})]
        (clef/classify! {:account-id account :key-file "/ignored"} "answer this")
        => :answer)
      (count @events) => 3
      (mapv :operation @events) => ["text" "classification" "classification"]
      (mapv #(get-in % [:model-usage 0 :input_tokens]) @events) => [3 8 9]
      (string/includes? (pr-str @events) "PRIVATE") => false)))

(fact "native rounds and delegated models record calls, not accumulated captions"
  (with-ledger
    (fn [events]
      (execution/in-event execution/request "ai delegate"
        (fn [request]
          (let [parent (assoc provider :name "parent" :model "p"
                                      :url "https://api.x.ai/v1")
                child (assoc parent :name "child" :model "c")
                toolset {:name "ledger" :kind "search" :servers []
                         :users ["U1"] :adapters ["slack"] :native-tools ["ai"]
                         :providers [{:name "parent" :models ["p"]}
                                     {:name "child" :models ["c"]}]}
                turns (atom 0)]
            (with-redefs [ai/config (constantly {:providers [parent child]})
                          mcp/config (constantly {:servers [] :toolsets [toolset]})
                          http/post
                          (fn [_ opts]
                            (let [parent? (= "p" (get-in opts [:form-params :model]))
                                  first? (and parent? (= 1 (swap! turns inc)))]
                              {:status 200
                               :body {:usage {:input_tokens 3 :output_tokens 2
                                              :cost_in_usd_ticks 10000000}
                                      :choices [{:message
                                                 (if first?
                                                   {:tool_calls [{:id "call-1" :type "function"
                                                                  :function {:name "yetibot_ai"
                                                                             :arguments
                                                                             (json/generate-string
                                                                               {"action" "ask"
                                                                                "provider" "child"
                                                                                "prompt" "PRIVATE-PROMPT"})}}]}
                                                   {:content "answer"})}]}}))]
              (binding [usage/*context-id* (usage/context-id)]
                (:result/value (tools/complete parent "p" []
                                  {:actor (policy/actor request)}))) => "answer"
              @turns => 2
              (count @events) => 3
              (count (set (map :context-id @events))) => 1
              (reduce + (map #(get-in % [:model-usage 0 :usd]) @events)) => 0.003M
              (string/includes? (pr-str @events) "PRIVATE") => false)))))))

(fact "initial ledger failure blocks sending and final failure preserves answers"
  (with-ledger
    (fn [events]
      (let [calls (atom 0)]
        (with-redefs [http/post (fn [& _] (swap! calls inc) {:status 200 :body body})]
          (with-redefs [store/begin! (fn [& _] (throw (Exception. "PRIVATE-DB")))]
            (try (ai/chat provider "one" [] {})
                 (catch Exception e (.getMessage e)))
            => "AI usage tracking is unavailable. No AI request was sent.")
          @calls => 0
          (with-redefs [store/finish! (fn [& _] (throw (Exception. "PRIVATE-DB")))]
            (ai/chat provider "one" [] {}) => body)
          @calls => 1
          (get-in @events [0 :state]) => "pending")))))
