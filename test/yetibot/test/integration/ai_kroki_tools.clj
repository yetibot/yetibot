(ns yetibot.test.integration.ai-kroki-tools
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [midje.sweet :refer [fact => throws]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-kroki-tools :as kroki]
            [yetibot.models.ai-native-tools :as native]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.ai-execution :as execution]))

(def toolset {:name "diagrams" :kind "search" :servers []
              :native-tools ["kroki_types" "kroki_render"]
              :users ["U1"] :adapters ["slack"]
              :providers [{:name "fake" :models ["fake"]}]})
(def artifact {:type "future-engine" :format "svg" :mime-type "image/svg+xml"
               :bytes 64 :id "owned-diagram" :ephemeral true
               :url "https://bot.example/generated-images/owned-diagram.svg"})

(defn boundary [calls operation & [request]]
  (case operation
    "validate-render!" request
    "types!" (public/reviewed
               {:result/value ["future-engine — 42"]
                :result/data {:engines [{:type "future-engine" :version "42"}]
                              :server-version "0.36.0" :stale false}})
    "render!" (do (swap! calls conj request)
                  (public/reviewed {:result/value (:url artifact)
                                    :result/data artifact :result/public-data artifact}))))

(defn run [last-turn & [render-result source]]
  (let [calls (atom []) sent (atom [])]
    (with-redefs [kroki/available-tools (constantly ["kroki_types" "kroki_render"])
                  kroki/client-call (fn [operation & args]
                                      (if (and render-result (= operation "render!"))
                                        (do (swap! calls conj (first args)) render-result)
                                        (apply boundary calls operation args)))
                  mcp/config (constantly {:servers [] :toolsets [toolset]})
                  ai/chat (fn [_ _ messages _]
                            (swap! sent conj messages)
                            (if (= 1 (count @sent))
                              {:choices [{:message
                                          {:role "assistant"
                                           :tool_calls [{:id "diagram-1" :type "function"
                                                         :function {:name "yetibot_kroki_render"
                                                                    :arguments (json/generate-string
                                                                                 {"type" "future-engine"
                                                                                  "source" (or source "literal | diagram `text`")})}}]}}]}
                              (last-turn)))]
      (execution/in-event execution/request "ai draw this"
        (fn [request]
          {:result (tools/complete {:name "fake"} "fake"
                                   [{:role "user" :content "Draw this."}]
                                   {:actor (policy/actor request)})
           :calls @calls :sent @sent})))))

(fact "native catalog/render definitions are discoverable without running diagrams"
  (get-in (native/definition "kroki_render") [:inputSchema :required])
  => ["type" "source"]
  (get-in (native/definition "kroki_types") [:annotations :readOnlyHint]) => true
  (kroki/arguments! {} "kroki_render" {"type" "x" "source" "x" "url" "https://elsewhere"})
  => (throws Exception)
  (kroki/arguments! {} "kroki_types" {"limit" 0}) => (throws Exception))

(fact "host delivers the actual artifact even when the assistant omits its URL"
  (let [{:keys [result calls sent]}
        (run (constantly {:choices [{:message {:role "assistant" :content "I made your diagram."}}]}))
        tool (first (filter #(= "tool" (:role %)) (last sent)))
        reply (json/parse-string (:content tool) true)]
    calls => [{:type "future-engine" :source "literal | diagram `text`"}]
    (get-in reply [:structuredContent :url]) => (:url artifact)
    (get-in result [:result/data :artifacts]) => [artifact]
    (get-in (public/projection result) [:result/public-data :artifacts]) => [artifact]
    (str/includes? (:result/value result) (:url artifact)) => true
    (str/includes? (pr-str (:result/public-data result)) "literal | diagram") => false))

(fact "native rendering admits source beyond the ordinary tool argument bound"
  (let [source (apply str (repeat 18000 "雪"))
        {:keys [result calls]}
        (run (constantly {:choices [{:message {:role "assistant" :content "Rendered."}}]})
             nil source)]
    (mapv :source calls) => [source]
    (get-in result [:result/data :artifacts]) => [artifact]))

(fact "remote tools cannot claim the native render argument allowance"
  (let [arguments (json/generate-string {"source" (apply str (repeat 18000 "x"))})]
    (#'tools/parse-call
     {:id "remote-1" :function {:name "remote_kroki_render" :arguments arguments}}
     {"remote_kroki_render" {:tool {:name "kroki_render" :max-argument-length 2100000}}}
     #{} {}) => (throws Exception)))

(fact "a later assistant failure retains generated artifacts without rerendering"
  (let [{:keys [result calls]}
        (run #(throw (Exception. "PRIVATE-PROVIDER-ERROR")))]
    (count calls) => 1
    (get-in result [:result/data :isError]) => true
    (:result/public-error result) => map?
    (str/includes? (:result/value result) (:url artifact)) => true
    (str/includes? (pr-str result) "PRIVATE-") => false))

(fact "render errors retain useful reviewed diagnostics, not only a generic trace"
  (let [failure (public/reviewed
                  {:result/error "Kroki rejected the diagram source or options."
                   :result/public-error {:service "kroki" :stage "render"
                                         :kind "invalid-source" :http-status 400 :line 7}})
        {:keys [result calls]}
        (run (constantly {:choices [{:message {:role "assistant" :content "The source needs fixing."}}]})
             failure)]
    (count calls) => 1
    (get-in result [:result/data :artifacts]) => nil
    (get-in result [:result/public-error :kroki 0 :kind]) => "invalid-source"
    (get-in result [:result/public-error :kroki 0 :line]) => 7
    (get-in result [:result/public-error :kroki 0 :type]) => "future-engine"))

(fact "untrusted and denied actors cannot render or discover"
  (with-redefs [kroki/client-call (fn [& _] (throw (AssertionError. "Unexpected plugin IO")))]
    (:isError (kroki/call! {:toolset toolset} "kroki_types" {} {})) => true
    (execution/in-event execution/request "ai draw"
      (fn [request]
        (:isError (kroki/call! {:toolset (assoc toolset :users ["other"])}
                               "kroki_render" {"type" "x" "source" "x"}
                               (:chat-source request))) => true))))
