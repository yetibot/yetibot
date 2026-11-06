(ns yetibot.test.integration.ai-defaults
  (:require [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as api]
            [yetibot.api.opencode :as zen]
            [yetibot.commands.ai :as command]
            [yetibot.core.config :as core-config]
            [yetibot.core.handler :as handler]
            [yetibot.core.parser :as parser]
            [yetibot.util.ai-execution :as execution]
            [yetibot.core.models.admin :as admin]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-model-tools :as native]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.ai-execution :as chat]))

(def settings
  {:default-provider "first"
   :providers [{:name "first" :url "https://first.example/v1"
                :model "original" :key "private-key"}
               {:name "second" :url "https://second.example/v1"
                :model "configured"}
               {:name "zen" :url "https://opencode.ai/inference/openai/v1"}]})

(defn isolated [f]
  (let [state (some-> (ns-resolve 'yetibot.api.ai 'runtime-default) deref)
        before (when state @state)
        get-config core-config/get-config]
    (try
      (when state (reset! state nil))
      (with-redefs [core-config/get-config (fn [spec path]
                                             (if (= [:ai] path)
                                               {:value settings}
                                               (get-config spec path)))
                    admin/user-is-admin? (constantly false)
                    zen/model-prices (constantly {})
                    context/messages (fn [& _] [])
                    tools/complete (fn [& _] {:result/value "ordinary answer"})]
        (f))
      (finally (when state (reset! state before))))))

(fact "a trusted user changes defaults atomically without changing file config"
  (isolated
    (fn []
      (get-in (chat/run-command chat/request "ai default second chosen")
              [:data :runtime_only]) => true
      (:default-provider (api/config)) => "second"
      (:model (api/provider (api/config) nil)) => "chosen"
      (get-in (core-config/get-config map? [:ai])
              [:value :default-provider]) => "first"
      (chat/in-event chat/request "ai hello"
        (fn [request]
          (let [prepared (command/prepare-prompt
                           (assoc request :match "--no-tools hello"))]
            [(get-in prepared [:provider :name]) (:model prepared)])))
      => ["second" "chosen"]
      (chat/in-event chat/request "ai hello"
        (fn [request]
          (let [prepared (command/prepare-prompt
                           (assoc request :match
                                  "--no-tools --provider first --model explicit hello"))]
            [(get-in prepared [:provider :name]) (:model prepared)])))
      => ["first" "explicit"]
      (get-in (chat/run-command chat/request "ai default reset")
              [:data :runtime_only]) => true
      (:default-provider (api/config)) => "first"
      (:model (api/provider (api/config) nil)) => "original")))

(fact "unknown providers and credential-valued models never become defaults"
  (isolated
    (fn []
      (chat/run-command chat/request "ai default missing chosen")
      (:default-provider (api/config)) => "first"
      (chat/run-command chat/request "ai default first private-key")
      (:model (api/provider (api/config) nil)) => "original")))

(fact "native AI control changes only defaults for trusted policy-allowed users"
  (isolated
    (fn []
      (chat/in-event chat/request "ai use second chosen as default"
        (fn [request]
          (let [config {:toolset {:kind "search"}}
                source (:chat-source request)
                selection {"action" "set_default"
                           "provider" "second" "model" "chosen"}]
            (get-in (native/call! config "ai" selection source)
                    [:structuredContent :runtime_only]) => true
            (:default-provider (api/config)) => "second"
            (get-in (native/call! config "ai" {"action" "defaults"} source)
                    [:structuredContent :model]) => "chosen"
            (get-in (native/call! config "ai" {"action" "reset_default"} source)
                    [:structuredContent :overridden]) => false
            (:default-provider (api/config)) => "first"
            (get-in (native/call! config "ai" {"action" "reset_default"} source)
                    [:structuredContent :overridden]) => false
            (:default-provider (api/config)) => "first"))))))

(fact "ordinary users can choose defaults but computed setters stay denied"
  (isolated
    (fn []
      (with-redefs [admin/user-is-admin? (constantly false)]
        (chat/run-command chat/request "ai default second chosen"))
      (:default-provider (api/config)) => "second"
      (chat/run-command chat/request "ai default reset")
      (chat/run-command chat/request "echo anything | ai default second chosen")
      (:default-provider (api/config)) => "first")))

(fact "thread delivery preserves provenance but cannot make controls direct"
  (isolated
    (fn []
      (let [request {:user {:id "U1" :username "alice"}
                     :chat-source
                     {:adapter :discord :uuid "discord" :room "parent"
                      :raw-event {:id "message" :channel-id "parent"
                                  :author {:id "U1"}}}}]
        (chat/in-event request "thread ai default second chosen"
          (fn [{:keys [chat-source user]}]
            ;; Same original raw event/caller as the adapter's delivery wrapper;
            ;; only the evaluated body loses its outer thread word.
            (with-redefs [yetibot.core.models.channel/settings-for-chat-source
                          (constantly {})]
              (execution/with-adapter-event
                (fn [source caller _ bot _]
                  (:error
                    (handler/handle-parsed-expr
                      source caller bot
                      (parser/parser "ai default second chosen"))))
                chat-source user :message {:id "BOT"}
                {:body "!ai default second chosen"}))))
        => "💥 Error in `ai default second chosen`: Send this AI control command directly in chat, without pipes, aliases, observers or computed commands. 💥"
        (:default-provider (api/config)) => "first"))))

(fact "explicit paid Zen defaults do not invoke automatic free selection"
  (isolated
    (fn []
      (with-redefs [zen/free-models
                    (fn [& _] (throw (AssertionError. "Explicit paid choice")))]
        (get-in (chat/run-command chat/request "ai default zen paid")
                [:data :model]) => "paid"
        (:default-provider (api/config)) => "zen"
        (api/default-model (api/provider (api/config) nil)) => "paid"
        (:ai/free-default (api/provider (api/config) nil)) => nil))))
