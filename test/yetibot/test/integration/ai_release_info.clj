(ns yetibot.test.integration.ai-release-info
  (:require [midje.sweet :refer [fact facts => truthy]]
            [yetibot.models.ai-native-tools :as native]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-toolsets :as toolsets]
            [yetibot.models.release-info :as releases]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.util.ai-execution :as context]))

(def provider {:name "fixture" :url "http://localhost/v1"})
(def config {:toolset {:name "read" :kind "search" :servers []
                       :native-tools ["release_info"]
                       :users ["U1"] :adapters ["slack"]
                       :providers [{:name "fixture" :models ["parent"]}]}})

(defn with-tool [f]
  (execution/in-event execution/request "ai show me new yetibot features"
    (fn [event]
      (let [tool (first (native/tools config (policy/actor event)
                                      provider "parent"))]
        (f tool)))))

(fact "release discovery is read-only typed metadata, not a command interpreter"
  (with-tool
    (fn [tool]
      (:name tool) => "release_info"
      (get-in tool [:annotations :readOnlyHint]) => true
      (get-in tool [:inputSchema :additionalProperties]) => false
      (get-in tool [:inputSchema :properties :view :enum])
      => ["running" "latest"]
      (let [result ((:host-call tool) {})]
        (get-in result [:structuredContent :untrusted]) => true
        (get-in result [:structuredContent :build]) => (:build releases/running)
        (seq (get-in result [:structuredContent :changes])) => truthy))))

(facts "static validation rejects URL/command injection and unbounded options"
  (doseq [args [{"url" "https://elsewhere.invalid"}
                {"command" "config show"} {"limit" 21}
                {"since" "2026-02-30"} {"view" "anything"}]]
    (with-tool
      (fn [tool]
        (fact (try ((:validate-arguments tool) args) false
                   (catch Exception _ true)) => true)))))

(fact "a captured tool does not retain authority after provenance changes"
  (with-tool
    (fn [tool]
      (binding [context/*event* nil]
        (try ((:host-call tool) {}) false (catch Exception _ true)) => true))))

(fact "policy revocation is checked before even public metadata is returned"
  (with-redefs [config (assoc-in config [:toolset :users] ["somebody-else"])]
    (with-tool
      (fn [tool]
        (try ((:host-call tool) {}) false (catch Exception _ true)) => true))))

(fact "defaults expose release discovery but explicit allowlists stay narrow"
  (boolean (some #{"release_info"} toolsets/default-native-tools)) => true
  (native/names {:toolset {:native-tools ["help"]}}) => ["help"])
