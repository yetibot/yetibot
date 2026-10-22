(ns yetibot.test.commands.ai-toolsets
  (:require [midje.sweet :refer [fact facts =>]]
            [yetibot.commands.ai :as ai]
            [yetibot.api.ai :as api]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-proposals :as proposals]
            [yetibot.models.ai-github :as github]
            [yetibot.test.models.ai-github :as fixture]
            [yetibot.test.models.ai-tool-policy :refer [actor]]))

(def request {:chat-source {:adapter :slack :uuid "workspace"
                            :raw-event {:user "U1" :channel "C1"
                                        :ts "now" :thread_ts "root"}}})
(def provider {:name "local" :url "http://localhost/v1" :model "chat"})

(facts "toolset flags are leading options and cannot conflict with no-tools"
  (ai/parse-prompt "--provider local --toolset search hello")
  => {:provider "local" :toolset "search" :prompt "hello"}
  (doseq [input ["--toolset x --no-tools hello" "--no-tools --toolset x hello"
                 "--toolset x --toolset y hi"]]
    (fact (map? (ai/prompt-cmd {:match input})) => true
          (contains? (ai/prompt-cmd {:match input}) :result/error) => true)))

(fact "request context reaches policy separately from conversation messages"
  (with-redefs [api/config (constantly {:providers [provider]})
                context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                tools/complete (fn [_ _ _ execution] execution)]
    (ai/prompt-cmd (assoc request :match "--toolset github-pr change it"))
    => {:toolset "github-pr" :actor actor}))

(fact "private toolsets and writer connections are hidden from unauthorized discovery"
  (with-redefs [mcp/config (constantly fixture/config)]
    (ai/toolsets-cmd {}) => []
    (ai/servers-cmd {}) => []
    (ai/toolsets-cmd request) => ["github-pr"]
    (ai/servers-cmd request) => ["reader"]))

(fact "approval is direct, one-shot, and never calls the model"
  (let [p (proposals/stage! fixture/t actor provider "chat"
                           {"owner" "owner" "repo" "repo" "base" "main"
                            "title" "Fix" "body" "Change"
                            "files" [{"path" "src/a.clj" "content" "new\n"}]}
                           (fn [& _] {:base-sha fixture/sha
                                      :originals {"src/a.clj" "old\n"}}))
        writes (atom 0)]
    (proposals/page! (:id p) actor 1)
    (with-redefs [mcp/config (constantly fixture/config)
                  api/config (constantly {:providers [provider]})
                  tools/complete (fn [& _] (throw (Exception. "must not infer")))
                  github/execute! (fn [_ _] (swap! writes inc)
                                    {:status :succeeded :pr-url "https://github.com/owner/repo/pull/1"})]
      (get-in (ai/approve-cmd (assoc request :match ["approve" (:id p)]))
              [:result/data :status]) => :succeeded
      (contains? (ai/approve-cmd (assoc request :match ["approve" (:id p)]))
                 :result/error) => true
      @writes => 1)))
