(ns yetibot.test.api.ai-environment
  (:require [dec :refer [explode]]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.config :as config]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.util.ai-config :as settings]))

(def expanded
  (get-in (explode
            {:yetibot-ai-default-provider "local"
             :yetibot-ai-providers-0-name "local"
             :yetibot-ai-providers-0-url "http://localhost/v1"
             :yetibot-ai-providers-0-model "chat"
             :yetibot-ai-providers-0-tool-calling "false"
             :yetibot-ai-providers-0-tool-choice "omit"
             :yetibot-ai-providers-0-gateway-id "chat-gateway"
             :yetibot-ai-intent-router-account-id "ACCOUNT"
             :yetibot-ai-intent-router-key-file "/tmp/token"
             :yetibot-ai-intent-router-gateway-id "structured-gateway"
             :yetibot-ai-mcp-default-toolset "search"
             :yetibot-ai-mcp-max-tool-calls "3"
             :yetibot-ai-mcp-servers-0-name "web"
             :yetibot-ai-mcp-servers-0-command "search-server"
             :yetibot-ai-mcp-servers-0-env-github-personal-access-token "FAKE_TOKEN"
             :yetibot-ai-mcp-servers-0-github-api-token "FAKE_REST_TOKEN"
             :yetibot-ai-mcp-servers-0-tools-0 "search"
             :yetibot-ai-mcp-toolsets-0-name "search"
             :yetibot-ai-mcp-toolsets-0-kind "search"
             :yetibot-ai-mcp-toolsets-0-servers-0-name "web"
             :yetibot-ai-mcp-toolsets-0-servers-0-tools-0 "search"})
          [:yetibot :ai]))

(fact "actual exploded environment normalizes before spec validation and use"
  (with-redefs [config/get-config
                (fn [spec path]
                  (let [value (if (= path [:ai]) expanded (:mcp expanded))]
                    (if (s/valid? spec value) {:value value} {:error :invalid})))]
    (:default-provider (ai/config)) => "local"
    (ai/tool-calling? (first (:providers (ai/config)))) => false
    (:tool-choice (first (:providers (ai/config)))) => "omit"
    (:gateway-id (first (:providers (ai/config)))) => "chat-gateway"
    (:gateway-id (:intent-router (ai/config))) => "structured-gateway"
    (:key-file (:intent-router (ai/config))) => "/tmp/token"
    (:max-tool-calls (mcp/config)) => "3"
    (get-in (mcp/config) [:servers 0 :env "GITHUB_PERSONAL_ACCESS_TOKEN"]) => "FAKE_TOKEN"
    (get-in (mcp/config) [:servers 0 :github-api-token]) => "FAKE_REST_TOKEN"
    (get-in (toolsets/select (mcp/config) nil) [:toolset :name]) => "search"))

(fact "Responses environment keys normalize before provider validation"
  (let [value (get-in
                (explode {:yetibot-ai-providers-0-name "grok"
                          :yetibot-ai-providers-0-url "https://api.x.ai/v1"
                          :yetibot-ai-providers-0-api "responses"
                          :yetibot-ai-providers-0-native-tools-0 "web_search"
                          :yetibot-ai-providers-0-native-tools-1 "x_search"
                          :yetibot-ai-providers-0-max-turns "4"})
                [:yetibot :ai])
        normalized (settings/ai value)]
    (s/valid? ::ai/config value) => true
    (get-in normalized [:providers 0 :native-tools])
    => ["web_search" "x_search"]
    (get-in normalized [:providers 0 :max-turns]) => "4"
    (settings/ai normalized) => normalized))

(fact "native toolset environment keys normalize separately from providers"
  (let [value (get-in
                (explode {:yetibot-ai-mcp-toolsets-0-native-tools-0 "help"
                          :yetibot-ai-mcp-toolsets-0-native-tools-1 "history"})
                [:yetibot :ai :mcp])
        normalized (settings/mcp value)]
    (get-in normalized [:toolsets 0 :native-tools]) => ["help" "history"]
    (settings/mcp normalized) => normalized))

(fact "documented native-only EDN recipe requires no remote server"
  (let [[_ example] (re-find
                      #"(?s)## Native Yetibot tools.*?```clojure\n(.*?)```"
                      (slurp "doc/AI.md"))
        value (edn/read-string example)]
    (s/valid? ::mcp/config value) => true
    (:servers (toolsets/select value "yetibot_read")) => []
    (get-in (toolsets/select value "yetibot_read") [:toolset :native-tools])
    => ["help" "history" "history_count" "release_info"]))

(fact "flat EDN environment names remain byte-for-byte compatible"
  (let [value {:servers [{:name "local" :command "server" :tools ["search"]
                          :env {:http_proxy "http://proxy" :no_proxy "localhost"
                                :Mixed_Case "value" :legacy-name "value"
                                "lower_case" "value" :UPPER "value"}}]}
        normalized (settings/mcp value)]
    (s/valid? ::mcp/config value) => true
    (get-in normalized [:servers 0 :env])
    => {"http_proxy" "http://proxy" "no_proxy" "localhost"
        "Mixed_Case" "value" "legacy-name" "value"
        "lower_case" "value" "UPPER" "value"}
    (settings/mcp normalized) => normalized
    (with-redefs [config/get-config (constantly {:value value})]
      (get-in (mcp/config) [:servers 0 :env "http_proxy"]) => "http://proxy")))

(fact "the explicit REST read token is redacted like MCP credentials"
  (mcp/redact "Bearer REST_READ_SECRET and REST_READ_SECRET"
              {:servers [{:github-api-token "REST_READ_SECRET"}]})
  => "Bearer [redacted] and [redacted]")

(fact "documented full EDN recipe validates and selects GitHub PR reads only"
  (let [[_ example] (re-find #"(?s)## Named toolsets:.*?```clojure\n(.*?)```"
                             (slurp "doc/AI.md"))
        value (edn/read-string example)]
    (s/valid? ::ai/config value) => true
    (s/valid? ::mcp/config (:mcp value)) => true
    (mapv :name (:servers (toolsets/select (:mcp value) "github-pr")))
    => ["github-read"]))
