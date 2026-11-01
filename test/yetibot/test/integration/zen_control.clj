(ns yetibot.test.integration.zen-control
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [midje.sweet :refer [fact =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.api.opencode :as zen]
            [yetibot.core.models.admin :as admin]
            [yetibot.models.ai-model-tools :as models]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.ai-execution :as execution]
            [yetibot.util.ai-execution :as host]))

(def provider {:name "zen" :url "https://opencode.ai/inference/openai/v1"
               :key "fixture-secret"})
(def parent {:name "parent" :url "https://parent.example/v1" :model "p"})
(def t {:name "free" :kind "search" :servers [] :native-tools ["ai"]
        :users ["U1"] :adapters ["slack"]
        :providers [{:name "parent" :models ["p"]}
                    {:name "zen" :models ["free-a"]}]})
(def config {:servers [] :toolset t})
(def settings {:providers [parent provider] :default-provider "parent"})
(def selection {"action" "set_default" "provider" "zen" "model" "free-a"})
(defn response []
  {:status 200 :body
   (java.io.ByteArrayInputStream.
     (.getBytes (json/generate-string
                  {:data [{:id "free-a"
                           :cost {:text {:input {:price 0}
                                         :output {:price 0}}}}]}) "UTF-8"))})

(fact "runtime selection requires trusted policy and never rewrites config"
  (execution/in-event execution/request "ai choose free Zen"
    (fn [request]
      (let [gets (atom 0) prefs (atom {})]
        (with-redefs [ai/config (constantly settings)
                      zen/preferences prefs
                      ai/runtime-default (atom nil)
                      admin/user-is-admin? (constantly true)
                      http/get (fn [& _] (swap! gets inc) (response))
                      ai/complete (fn [& _] (throw (AssertionError. "No POST")))]
          (get-in (models/call! config "ai" selection (:chat-source request))
                  [:structuredContent :verified_free]) => true
          @ai/runtime-default => {:provider "zen" :model "free-a"}
          @prefs => {}
          (ai/config) => settings
          @gets => 1
          (doseq [args [(assoc selection "model" "paid")
                        (assoc selection "provider" "parent")
                        (assoc selection "url" "https://evil.example")
                        (assoc selection "prompt" "hidden")]]
            (fact (:isError (models/call! config "ai" args
                                         (:chat-source request))) => true))
          @gets => 1
          (:isError (models/call! config "ai" selection
                                  (assoc (:chat-source request) :room "other")))
          => true
          @gets => 1)))))

(fact "revocation during catalog lookup prevents the runtime mutation"
  (execution/in-event execution/request "ai choose free Zen"
    (fn [request]
      (let [revoked (atom false) prefs (atom {}) sensitive! host/sensitive!]
        (with-redefs [ai/config (constantly settings)
                      zen/preferences prefs
                      ai/runtime-default (atom nil)
                      admin/user-is-admin? (constantly true)
                      http/get (fn [& _] (reset! revoked true) (response))
                      host/sensitive! (fn []
                                        (if @revoked (throw (Exception.))
                                          (sensitive!)))]
          (:isError (models/call! config "ai" selection (:chat-source request)))
          => true
          @prefs => {})))))

(fact "whole-batch validation blocks a valid setter followed by invalid args"
  (execution/in-event execution/request "ai choose free Zen"
    (fn [request]
      (let [gets (atom 0) prefs (atom {})]
        (with-redefs [ai/config (constantly settings)
                      mcp/config (constantly {:servers [] :toolsets [t]})
                      zen/preferences prefs
                      ai/runtime-default (atom nil)
                      admin/user-is-admin? (constantly true)
                      http/get (fn [& _] (swap! gets inc) (response))
                      ai/chat
                      (fn [& _]
                        {:choices [{:message
                          {:tool_calls
                           (mapv (fn [i args]
                                   {:id (str i) :type "function"
                                    :function {:name "yetibot_ai"
                                      :arguments (json/generate-string args)}})
                                 (range)
                                 [selection (assoc selection "key" "bad")])}}]})]
          (try (tools/complete parent "p" []
                  {:toolset "free" :actor (policy/actor request)})
               :unsafe (catch Exception _ :blocked)) => :blocked
          @gets => 0
          @prefs => {})))))
