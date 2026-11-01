(ns yetibot.test.integration.ai-cost
  (:require [cheshire.core :as json]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.util.ai-cost :as cost]))

(def provider {:name "grok" :url "https://api.x.ai/v1" :model "grok-4.7"})

(def cloudflare
  {:name "cloudflare"
   :url (str "https://api.cloudflare.com/client/v4/accounts/"
             "0123456789abcdef0123456789abcdef/ai/v1")})

(fact "Workers AI Gemma usage is priced, unknown prices retain measured units"
  (let [model "@cf/google/gemma-4-26b-a4b-it"
        body {:usage {:prompt_tokens 18 :completion_tokens 96
                      :total_tokens 114 :neurons 2.781818151473999}}
        priced (cost/turn cloudflare model body)
        neurons (cost/turn cloudflare "unknown" body)
        tokens (cost/turn cloudflare "unknown"
                 {:usage {:prompt_tokens 18 :completion_tokens 96}})]
    priced => {:usd 0.0000306M :kind :estimate :scope :tokens :turns 1}
    (cost/label priced) => "tokens est. <$0.0001"
    (cost/label neurons) => "2.78 neurons"
    (cost/label tokens) => "18 input / 96 output tokens"
    (cost/label (cost/add neurons neurons)) => "5.56 neurons"
    (cost/label (cost/add tokens tokens)) => "36 input / 192 output tokens"
    (cost/add neurons tokens) => nil
    (cost/add neurons nil) => nil
    (cost/turn (assoc cloudflare :url "https://other.example/v1") model body)
    => nil
    (cost/turn cloudflare model {:usage {:prompt_tokens -1}}) => nil
    (cost/turn cloudflare model
      {:usage {:prompt_tokens 10 :completion_tokens "20"}}) => nil
    (cost/turn cloudflare "unknown" {:usage {:neurons Double/NaN}}) => nil
    (get-in
      (with-redefs [ai/chat (fn [& _] (assoc body :choices
                                        [{:message {:content "brooo"}}]))]
        (ai/complete cloudflare model []))
      [:result/data :ai/cost]) => priced))

(fact "actual ticks are per-request USD, not token estimates"
  (cost/turn provider "any-model" {:usage {:cost_in_usd_ticks 37756000}})
  => {:usd 0.0037756M :kind :reported :scope :api :turns 1}
  (cost/turn (assoc provider :url "https://other.example/v1") "grok-4.7"
             {:usage {:cost_in_usd_ticks 37756000}}) => nil
  (doseq [bad [-1 "2000" Double/NaN Double/POSITIVE_INFINITY]]
    (fact (cost/turn provider "grok-4.7"
                     {:usage {:cost_in_usd_ticks bad}}) => nil)))

(fact "unknown providers cannot inject host accounting through response data"
  (with-redefs [ai/chat (fn [& _]
                         {:choices [{:message {:content "Answer"}}]
                          :ai/cost {:usd 0M :kind :reported :scope :api}})]
    (get-in (ai/complete {:url "https://other.example/v1"} "model" [])
            [:result/data :ai/cost]) => nil))

(fact "token estimates account for cache and the long-context rate boundary"
  (:usd (cost/turn provider "grok-4.7"
          {:usage {:input_tokens 1000 :output_tokens 100
                   :input_tokens_details {:cached_tokens 500}}})) => 0.00185M
  (:usd (cost/turn provider "grok-4.7"
          {:usage {:input_tokens 200000 :output_tokens 1000}})) => 0.812M
  (cost/turn provider "unknown" {:usage {:input_tokens 1000 :output_tokens 10}})
  => nil
  (cost/turn provider "grok-4.7" {:usage {:input_tokens 10}}) => nil
  (cost/add (cost/add cost/initial nil)
            (cost/turn provider "grok-4.7"
                       {:usage {:cost_in_usd_ticks 10000000}})) => nil
  (cost/label {:usd Double/NaN :kind :reported :scope :api}) => "cost n/a"
  (cost/label {:usd 0.000001M :kind :estimate :scope :tokens})
  => "tokens est. <$0.0001")

(fact "zero estimates require a host-owned fresh free selection proof"
  (let [zen {:name "zen" :url "https://opencode.ai/inference/openai/v1"}]
    (cost/turn zen "free-a" {}) => nil
    (cost/turn (assoc zen :ai/verified-free true) "free-a" {})
    => {:usd 0M :kind :estimate :scope :api :turns 1}))

(fact "live estimates are deterministic and scoped to visible text"
  (let [messages [{:role "user" :content "hello"}]
        estimate (cost/visible-estimate provider "grok-4.7" messages "Hello")]
    (:scope estimate) => :visible-text
    (:kind estimate) => :estimate
    (:usd estimate) => 0.000024M
    (cost/visible-estimate provider "grok-4.7"
      [{:content [{:type "input_image" :image_url "private"}]}] "Hello") => nil))

(fact "tool-loop accounting sums every parent turn, not just the last usage"
  (execution/in-event execution/request "ai hello"
    (fn [request]
      (let [calls (atom 0)
            t {:name "search" :kind "search" :servers [] :native-tools ["ai"]
               :users ["U1"] :adapters ["slack"]
               :providers [{:name "grok" :models ["grok-4.7"]}]}]
        (with-redefs [ai/config (constantly {:providers [provider]})
                      mcp/config (constantly {:servers [] :toolsets [t]})
                      ai/chat
                      (fn [& _]
                        (if (= 1 (swap! calls inc))
                          {:usage {:cost_in_usd_ticks 10000000}
                           :choices [{:message {:tool_calls
                             [{:id "one" :type "function"
                               :function {:name "yetibot_ai"
                                 :arguments (json/generate-string
                                              {"action" "providers"})}}]}}]}
                          {:usage {:cost_in_usd_ticks 5000000}
                           :choices [{:message {:content "Answer"}}]}))]
          (get-in (tools/complete provider "grok-4.7" []
                    {:actor (policy/actor request)}) [:result/data :ai/cost])
          => {:usd 0.0015M :kind :reported :scope :api :turns 2}
          @calls => 2)))))
