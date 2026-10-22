(ns yetibot.test.api.ai-capabilities
  (:require [clj-http.client :as client]
            [clojure.spec.alpha :as s]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.models.ai-tools :as tools]
            [yetibot.api.mcp :as mcp]))

(facts "provider capability values are validated, including environment booleans"
  (doseq [value [true false "true" "false"]]
    (fact (s/valid? ::ai/provider
                    {:name "local" :url "http://localhost/v1"
                     :tool-calling value :tool-choice "omit"}) => true))
  (s/valid? ::ai/provider {:name "local" :url "http://localhost/v1"
                          :tool-calling "maybe"}) => false
  (s/valid? ::ai/provider {:name "local" :url "http://localhost/v1"
                          :tool-choice "anything"}) => false)

(fact "compatibility mode omits tool_choice but retains function definitions"
  (let [request (atom nil)]
    (with-redefs [client/post (fn [_ options]
                               (reset! request (:form-params options))
                               {:status 200 :body {}})]
      (ai/chat {:name "local" :url "http://localhost/v1"
                :tool-choice "omit"} "chat" []
               {:tools [{:type "function"}] :tool_choice "none"})
      (contains? @request :tool_choice) => false
      (:tools @request) => [{:type "function"}])))

(fact "disabled tool support fails before connecting, not by silently falling back"
  (let [connected (atom false)]
    (with-redefs [mcp/config (constantly {:servers [{:name "search" :tools ["search"]}]})
                  mcp/with-tools (fn [& _] (reset! connected true))]
      (try (tools/complete {:tool-calling "false"} "chat" [])
           (catch Exception _ :disabled)) => :disabled
      @connected => false)))

(fact "filtered connections retain redaction of unselected writer credentials"
  (mcp/redact "do not expose writer-secret"
              {:servers []
               :redaction-servers [{:name "writer"
                                    :env {"TOKEN" "writer-secret"}}]})
  => "do not expose [redacted]")
