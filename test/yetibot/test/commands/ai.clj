(ns yetibot.test.commands.ai
  (:require [clj-http.client :as client]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as api]
            [yetibot.commands.ai :as ai]
            [yetibot.core.hooks :as hooks]
            [yetibot.models.ai-context :as context]))

(def local-provider {:name "local" :url "http://localhost:11434/v1" :model "llama3"})
(def hosted-provider {:name "hosted" :url "https://example.com/v1" :key "secret" :model "chat"})
(def config {:providers [local-provider hosted-provider] :default-provider "hosted"})

(facts "leading options and multiline prompts"
  (ai/parse-prompt "hello\nworld --model literal")
  => {:prompt "hello\nworld --model literal"}
  (ai/parse-prompt "--model org/model --provider local explain this")
  => {:provider "local" :model "org/model" :prompt "explain this"}
  (ai/parse-prompt "-- --provider is part of my prompt")
  => {:prompt "--provider is part of my prompt"}
  (doseq [input ["" " " "--" "--provider" "--model x" "--unknown x hello"
                 "--provider --model x hi" "--model x --model y hi"]]
    (fact (ai/prompt-cmd {:match input}) => {:result/error ai/usage})))

(facts "registered command routing"
  (let [[_ commands] (hooks/find-sub-cmds "ai")]
    (fact
      (second (hooks/match-sub-cmds "providers" commands)) => #'ai/providers-cmd
      (second (hooks/match-sub-cmds "models" commands)) => #'ai/models-cmd
      (second (hooks/match-sub-cmds "models local" commands)) => #'ai/models-cmd
      (second (hooks/match-sub-cmds "hello\nworld" commands)) => #'ai/prompt-cmd)))

(facts "provider and model selection"
  (with-redefs [api/config (constantly config)
                context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                api/complete (fn [provider model messages] [(:name provider) model messages])]
    (ai/providers-cmd {}) => ["local" "hosted"]
    (ai/prompt-cmd {:match "hello"})
    => ["hosted" "chat" [{:role "user" :content "hello"}]]
    (ai/prompt-cmd {:match "--provider local hello"})
    => ["local" "llama3" [{:role "user" :content "hello"}]]
    (ai/prompt-cmd {:match "--model other --provider local hello"})
    => ["local" "other" [{:role "user" :content "hello"}]]
    (ai/prompt-cmd {:match "--model other hello"})
    => ["hosted" "other" [{:role "user" :content "hello"}]]
    (ai/prompt-cmd {:match "--provider missing hello"})
    => {:result/error "Unknown AI provider. Use ai providers to list available providers."})
  (with-redefs [api/config (constantly {:providers [(dissoc local-provider :model)]})]
    (ai/prompt-cmd {:match "hello"})
    => {:result/error "No default model configured for this provider. Use --model <model> or configure its model."})
  (api/provider (dissoc config :default-provider) nil) => local-provider)

(facts "models stay grouped, even when one provider fails"
  (with-redefs [api/config (constantly config)
                api/models (fn [provider]
                             (case (:name provider)
                               "local" ["llama3" "org/model"]
                               "hosted" (api/fail "Provider unavailable.")))]
    (ai/models-cmd {:match ["models" nil]})
    => {:result/data {"local" {:models ["llama3" "org/model"]}
                     "hosted" {:result/error "Provider unavailable."}}
        :result/value ["local: llama3, org/model" "hosted: Provider unavailable."]}
    (ai/models-cmd {:match ["models local" "local"]})
    => {:result/data {"local" {:models ["llama3" "org/model"]}}
        :result/value ["local: llama3, org/model"]}
    (ai/models-cmd {:match ["models missing" "missing"]})
    => {:result/error "Unknown AI provider. Use ai providers to list available providers."})
  (with-redefs [api/config (constantly config) api/models (constantly [])]
    (:result/value (ai/models-cmd {:match ["models local" "local"]}))
    => ["local: No models available."]))

(facts "context is read for each request, without reusing previous prompts"
  (let [requests (atom [])]
    (with-redefs [api/config (constantly config)
                  client/post (fn [_ options]
                                (swap! requests conj (:form-params options))
                                {:status 200 :body {:choices [{:message {:content "answer"}}]}})]
      (:result/value (ai/prompt-cmd {:match "first"})) => "answer"
      (:result/value (ai/prompt-cmd {:match "second"})) => "answer"
      (mapv :messages @requests)
      => [[{:role "user" :content "first"}] [{:role "user" :content "second"}]])))

(fact "a failed thread read does not send a prompt"
  (let [sent? (atom false)]
    (with-redefs [api/config (constantly config)
                  context/messages (fn [& _] (api/fail "Could not read the thread."))
                  api/complete (fn [& _] (reset! sent? true))]
      (ai/prompt-cmd {:match "hello"}) => {:result/error "Could not read the thread."}
      @sent? => false)))
