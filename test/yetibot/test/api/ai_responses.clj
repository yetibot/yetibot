(ns yetibot.test.api.ai-responses
  (:require [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]))

(def provider
  {:name "grok" :url "https://api.x.ai/v1/" :key "provider-secret"
   :model "grok-4.7" :api "responses"
   :native-tools ["web_search" "x_search"] :max-turns 3})

(def answer
  {:id "resp-final" :status "completed"
   :output [{:type "reasoning" :encrypted_content "opaque-state"}
            {:type "message" :role "assistant"
             :content [{:type "output_text" :text "Account: Yetibot"}
                       {:type "output_text" :text " (verified)"}]}]
   :usage {:input_tokens 10 :output_tokens 5}})

(defn error-message [f]
  (try (f) (catch Exception e (.getMessage e))))

(facts "Responses capabilities are explicit and validated"
  (s/valid? ::ai/provider provider) => true
  (s/valid? ::ai/provider (assoc provider :max-turns "4")) => true
  (s/valid? ::ai/provider (assoc provider :max-turns 20)) => true
  (doseq [invalid [(assoc provider :api "unknown")
                   (assoc provider :api "chat-completions")
                   (assoc provider :native-tools ["execute"])
                   (assoc provider :native-tools ["x_search" "x_search"])
                   (assoc provider :max-turns 0)
                   (assoc provider :max-turns 21)
                   (assoc provider :max-turns "999999999999999999")]]
    (fact (s/valid? ::ai/provider invalid) => false)))

(fact "Responses plain completion uses native search and joins output text"
  (let [requests (atom [])
        messages [{:role "user" :content "Who are we?"}]]
    (with-redefs [client/post (fn [url options]
                               (swap! requests conj
                                      [url (:form-params options)])
                               {:status 200 :body answer})]
      (ai/complete provider "grok-other" messages)
      => {:result/value "Account: Yetibot (verified)" :result/data answer}
      @requests
      => [["https://api.x.ai/v1/responses"
           {:model "grok-other" :input messages :store false
            :include ["reasoning.encrypted_content"] :max_turns 3
            :tools [{:type "web_search"} {:type "x_search"}]}]])))

(fact "Zen Responses uses the restored model without xAI-only max_turns"
  (let [captured (atom nil)
        zen {:name "zen" :url "https://opencode.ai/inference/openai/v1"
             :key "fixture-key" :model "gpt-5.4-mini" :api "responses"}]
    (with-redefs [client/post (fn [url options]
                               (fact url
                                     => "https://opencode.ai/inference/openai/v1/responses")
                               (reset! captured (:form-params options))
                               {:status 200 :body answer})]
      (:result/value (ai/complete zen "gpt-5.4-mini" []
                                  {:tools [] :tool_choice "none"}))
      => "Account: Yetibot (verified)"
      (:model @captured) => "gpt-5.4-mini"
      (contains? @captured :max_turns) => false
      (:tools @captured) => [])))

(fact "Responses function definitions are flat and coexist with native tools"
  (let [captured (atom nil)
        function {:name "cloudflare_whoami" :description "Read account"
                  :parameters {:type "object" :properties {}}}]
    (with-redefs [client/post (fn [_ options]
                               (reset! captured (:form-params options))
                               {:status 200 :body answer})]
      (ai/chat (dissoc provider :max-turns) "grok-4.7" []
               {:tools [{:type "function" :function function}]
                :tool_choice "auto"})
      (:max_turns @captured) => 6
      (:tools @captured)
      => [{:type "web_search"} {:type "x_search"}
          (assoc function :type "function")]
      (:tool_choice @captured) => "auto")))

(fact "disabling Responses tools omits tool choice without an override"
  (let [captured (atom nil)]
    (with-redefs [client/post (fn [_ options]
                               (reset! captured (:form-params options))
                               {:status 200 :body answer})]
      (ai/chat provider "grok-4.7" []
               {:tools [{:type "function" :function {:name "not-offered"}}]
                :tool_choice "none"})
      (:tools @captured) => []
      (contains? @captured :tool_choice) => false)))

(fact "disabling tools removes definitions, even for tool-choice omit providers"
  (let [captured (atom nil)]
    (with-redefs [client/post (fn [_ options]
                               (reset! captured (:form-params options))
                               {:status 200 :body answer})]
      (ai/chat (assoc provider :tool-choice "omit") "grok-4.7" []
               {:tools [{:type "function" :function {:name "not-offered"}}]
                :tool_choice "none"})
      (:tools @captured) => []
      (contains? @captured :tool_choice) => false)))

(facts "failed or incomplete Responses output does not masquerade as an answer"
  (doseq [body [(assoc answer :status "incomplete")
                (assoc answer :status "failed" :error {:message "secret"})
                {:status "completed" :output []}]]
    (with-redefs [client/post (fn [& _] {:status 200 :body body})]
      (fact (error-message #(ai/complete provider "grok-4.7" []))
            => "AI provider grok returned no completed text response."))))
