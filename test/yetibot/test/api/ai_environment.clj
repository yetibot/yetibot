(ns yetibot.test.api.ai-environment
  (:require [dec :refer [explode]]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.config :as config]
            [yetibot.models.ai-toolsets :as toolsets]))

(def expanded
  (get-in (explode
            {:yetibot-ai-default-provider "local"
             :yetibot-ai-providers-0-name "local"
             :yetibot-ai-providers-0-url "http://localhost/v1"
             :yetibot-ai-providers-0-model "chat"
             :yetibot-ai-providers-0-tool-calling "false"
             :yetibot-ai-providers-0-tool-choice "omit"
             :yetibot-ai-mcp-default-toolset "search"
             :yetibot-ai-mcp-max-tool-calls "3"
             :yetibot-ai-mcp-servers-0-name "web"
             :yetibot-ai-mcp-servers-0-command "search-server"
             :yetibot-ai-mcp-servers-0-env-github-personal-access-token "FAKE_TOKEN"
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
    (:max-tool-calls (mcp/config)) => "3"
    (get-in (mcp/config) [:servers 0 :env "GITHUB_PERSONAL_ACCESS_TOKEN"]) => "FAKE_TOKEN"
    (get-in (toolsets/select (mcp/config) nil) [:toolset :name]) => "search"))

(fact "documented full EDN recipe validates and selects GitHub PR reads only"
  (let [[_ example] (re-find #"(?s)## Named toolsets:.*?```clojure\n(.*?)```"
                             (slurp "doc/AI.md"))
        value (edn/read-string example)]
    (s/valid? ::ai/config value) => true
    (s/valid? ::mcp/config (:mcp value)) => true
    (mapv :name (:servers (toolsets/select (:mcp value) "github-pr")))
    => ["github-read"]))
