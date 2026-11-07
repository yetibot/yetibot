(ns yetibot.test.integration.ai-limits
  (:require [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as api]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.ai]
            [yetibot.core.models.admin :as admin]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.models.ai-limits :as fixture]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.util.ai-limits :as limits]))

(fact "trusted runtime controls are offline and flags win on the real command path"
  (fixture/isolated
    (fn []
      (let [calls (atom [])
            forbidden (fn [& _] (throw (AssertionError. "Unexpected IO")))]
        (with-redefs [admin/user-is-admin? (constantly true)
                      api/config (constantly
                                  {:providers [{:name "local" :model "chat"
                                                :url "http://local/v1"}]})
                      api/models forbidden mcp/with-tools forbidden
                      context/messages (fn [_ text]
                                         [{:role "user" :content text}])
                      api/complete (fn [_ _ _ options]
                                     (swap! calls conj options)
                                     {:result/value "answer"})
                      tools/complete forbidden]
          (:value (execution/run-command execution/request "ai limits"))
          => string?
          (execution/run-command execution/request
            "ai limits --max-output-tokens 65536 --max-tool-calls 40")
          @limits/runtime => {:max-output-tokens 65536 :max-tool-calls 40}
          @calls => []
          (execution/run-command execution/request "ai --no-tools hello")
          (:max-output-tokens (last @calls)) => 65536
          (let [result (execution/run-command execution/request
                         "ai --no-tools --max-output-tokens 131072 hello")]
            (:max-output-tokens (last @calls)) => 131072
            (get-in result [:data :ai/limits])
            => {:generation_limit 131072 :tool_call_limit 40})
          (execution/run-command execution/request
            "echo supplied | ai limits --max-output-tokens 1")
          @limits/runtime => {:max-output-tokens 65536 :max-tool-calls 40}
          (with-redefs [admin/user-is-admin? (constantly false)]
            (execution/run-command execution/request "ai limits reset")
            @limits/runtime => {:max-output-tokens 65536 :max-tool-calls 40})
          (execution/run-command execution/request "ai limits reset")
          @limits/runtime => {}
          (count @calls) => 2)))))
