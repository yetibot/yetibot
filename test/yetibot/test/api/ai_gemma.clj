(ns yetibot.test.api.ai-gemma
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.gemma-tools :as gemma]
            [yetibot.test.api.ai-cloudflare-messages :refer [provider]]
            [yetibot.test.api.gemma-tools :refer [screenshot]]))

(def model "@cf/google/gemma-4-26b-a4b-it")
(def options {:tools [{:type "function" :function {:name "yetibot_ai"}}]
              :tool_choice "auto"})
(defn body [text] {:choices [{:finish_reason "stop"
                             :message {:role "assistant" :content text}}]})
(defn turn [p m opts response]
  (with-redefs [http/post (fn [& _] {:status 200 :body response})]
    (ai/chat p m [{:role "user" :content "Question"}] opts)))
(defn error [f]
  (try (f) nil (catch Exception e [(.getMessage e) (ex-data e) (.getCause e)])))

(fact "wire normalization fixes the screenshot and preserves call IDs"
  (let [result (turn provider model options (body screenshot))
        message (ai/message provider result)
        calls (:tool_calls message)
        id (:id (first calls))
        replies [{:role "tool" :tool_call_id id :content "Paris"}]
        continuation (ai/continue-messages provider [] result replies)]
    (:content message) => nil
    (count calls) => 1
    (get-in calls [0 :function :name]) => "yetibot_ai"
    (get (json/parse-string (get-in calls [0 :function :arguments])) "prompt")
    => "What is the capital of France?"
    (boolean (re-matches #"gemma-[0-9a-f-]+" id)) => true
    (ai/message provider result) => message
    (:tool_calls (first continuation)) => calls
    (:tool_call_id (second continuation)) => id
    (ai/continue-messages provider [] result replies) => continuation))

(fact "structured tool calls are authoritative even with native content"
  (let [result (assoc-in (body screenshot) [:choices 0 :message :tool_calls]
                        [{:id "official" :type "function"
                          :function {:name "fixture" :arguments "{}"}}])]
    (turn provider model options result) => result))

(facts "only exact official CF Gemma model gets native compatibility"
  (doseq [[p m] [[provider "@cf/meta/llama-3.1-8b-instruct-fp8"]
                 [provider "gemma-4-26b-a4b-it"]
                 [(assoc provider :url "https://example.test/v1") model]]]
    (fact (turn p m options (body screenshot)) => (body screenshot))))

(facts "no-tools, length-limited and malformed control fail safely"
  (doseq [[p opts response]
          [[provider {} (body screenshot)]
           [provider (assoc options :tools []) (body screenshot)]
           [provider (assoc options :tool_choice "none") (body screenshot)]
           [(assoc provider :tool-calling false) options (body screenshot)]
           [provider options
            (assoc-in (body screenshot) [:choices 0 :finish_reason] "length")]
           [provider options (body (str screenshot " trailing"))]
           [provider options (body "<|tool_call>call:yetibot_ai{bad:")]
           [provider options (body "<|tool_call")]
           [provider options
            (body "<|channel>thought\nPRIVATE-THOUGHT<channel|>")]
           [provider options (body "<|tool_response>response:fixture{}")]]]
    (fact (error #(turn p model opts response))
          => [gemma/error-message {:ai/error true} nil])))

(fact "prose, code and quoted examples are not classified as commands"
  (doseq [text [(str "Example: " screenshot) (str "```" screenshot "```")
                (str "\"" screenshot "\"") "Ordinary answer"]]
    (fact (turn provider model options (body text)) => (body text))))
