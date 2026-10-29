(ns yetibot.test.api.ai-cloudflare-messages
  (:require [clj-http.client :as http]
            [midje.sweet :refer :all]
            [yetibot.api.ai :as ai]))

(def provider
  {:name "cloudflare" :api "chat-completions" :key "fixture-only"
   :url (str "https://api.cloudflare.com/client/v4/accounts/"
             "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/ai/v1")})

(def calls
  [{:id "call-one" :type "function"
    :function {:name "fixture" :arguments "{}"}}])

(defn wire-messages [p messages]
  (let [captured (atom nil)]
    (with-redefs [http/post (fn [_ options]
                             (reset! captured (:form-params options))
                             {:status 200 :body {:choices []}})]
      (ai/chat p "@cf/meta/llama-3.1-8b-instruct-fp8" messages
               {:tools [] :tool_choice "none"})
      (:messages @captured))))

(fact "Cloudflare follow-ups use non-null content without changing call IDs"
  (let [history [{:role "user" :content "Use the fixture."}
                 {:role "assistant" :content nil :tool_calls calls}
                 {:role "tool" :tool_call_id "call-one" :content "{}"}]
        result (wire-messages provider history)]
    (get-in result [1 :content]) => ""
    (get-in result [1 :tool_calls]) => calls
    (nth result 2) => (nth history 2)
    (get-in history [1 :content]) => nil))

(fact "Cloudflare accepts plain text instead of all-text content arrays"
  (wire-messages provider
                 [{:role "user" :content [{:type "text" :text "One"}
                                          {:type "text" :text "Two"}]}])
  => [{:role "user" :content "One\nTwo"}])

(fact "Cloudflare image and unknown content parts are never dropped"
  (let [messages
        [{:role "user"
          :content [{:type "text" :text "Describe"}
                    {:type "image_url"
                     :image_url {:url "https://example.test/image.png"}}]}
         {:role "user" :content [{:type "unknown" :text "Keep this"}]}]]
    (wire-messages provider messages) => messages))

(fact "generic Chat Completions providers keep their original messages"
  (let [messages [{:role "assistant" :content nil :tool_calls calls}
                  {:role "user" :content [{:type "text" :text "Text"}]}]]
    (wire-messages (assoc provider :name "other"
                         :url "https://provider.example/v1") messages)
    => messages))
