(ns yetibot.test.models.ai-tool-policy
  (:require [midje.sweet :refer [fact facts =>]]
            [yetibot.models.ai-tool-policy :as policy]))

(def actor {:adapter "slack" :connection "workspace" :user "U1"
            :conversation "workspace/C1" :thread "root"})
(def toolset {:name "github" :kind "github-read" :users ["U1"]
              :conversations ["workspace/C1"] :adapters ["slack"]
              :repositories ["owner/repo"]
              :providers [{:name "local" :models ["chat"]}]})
(defn error? [f] (try (f) false (catch Exception _ true)))

(fact "trusted context uses immutable IDs rather than display names or message text"
  (policy/actor {:chat-source {:adapter :slack :uuid "workspace"
                              :raw-event {:user "U1" :channel "C1"
                                          :thread_ts "root" :ts "now"}}
                 :user {:name "admin"}}) => actor
  (policy/actor {:chat-source {:adapter :slack :uuid "workspace"
                              :raw-event {:subtype "message_changed"
                                          :user "U1" :channel "C1" :ts "now"}}}) => nil)

(facts "identity and provider/model policy fail closed"
  (policy/authorize! toolset actor {:name "local"} "chat") => toolset
  (doseq [[a p m] [[nil {:name "local"} "chat"]
                    [(assoc actor :user "U2") {:name "local"} "chat"]
                    [(assoc actor :conversation "workspace/C2") {:name "local"} "chat"]
                    [actor {:name "cloud"} "chat"]
                    [actor {:name "local"} "other"]]]
    (fact (error? #(policy/authorize! toolset a p m)) => true)))

(facts "GitHub arguments cannot escape the repository allowlist"
  (policy/arguments! toolset "get_commit" {"owner" "owner" "repo" "repo" "sha" "main"})
  => {"owner" "owner" "repo" "repo" "sha" "main"}
  (doseq [[name args] [["get_commit" {"owner" "other" "repo" "repo"}]
                       ["search_code" {"query" "repo:owner/repo OR repo:other/private"}]
                       ["issue_read" {"owner" "owner" "repo" "repo" "method" "delete"}]
                       ["push_files" {"owner" "owner" "repo" "repo"}]]]
    (fact (error? #(policy/arguments! toolset name args)) => true))
  (policy/arguments! toolset "search_code" {"query" "map reduce"})
  => {"query" "(map reduce) repo:owner/repo"})
