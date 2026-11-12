(ns yetibot.test.integration.ai-command-charts
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [clj-http.client :as http]
            [midje.sweet :refer :all]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.weather :as weather]
            [yetibot.commands.ai :as ai-command]
            [yetibot.models.ai-context :as context]
            [yetibot.core.commands.alias :as alias]
            [yetibot.core.commands.collections]
            [yetibot.core.commands.render]
            [yetibot.core.handler :as handler]
            [yetibot.core.hooks :as hooks]
            [yetibot.core.chat :as chat]
            [yetibot.core.adapters.adapter :as adapters]
            [yetibot.core.models.users :as users]
            [yetibot.core.models.channel :as channel]
            [yetibot.core.models.history :as history]
            [yetibot.core.util.public-result :as public]
            [yetibot.models.ai-kroki-tools :as kroki]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.integration.ai-command-capture :as fixtures]
            [yetibot.test.support.ai-execution :as support]))

(def artifact {:type "vegalite" :format "png" :mime-type "image/png"
               :bytes 64 :id "owned-chart" :ephemeral true
               :url "https://bot.example/generated-images/owned-chart.png"})
(def toolset {:name "charts" :kind "search" :servers []
              :native-tools ["command" "chart"] :users ["U1"] :adapters ["slack"]
              :providers [{:name "fake" :models ["fake"]}]})
(defn call [id operation args]
  {:choices [{:message {:role "assistant" :content "Discard this ![]()"
                       :tool_calls [{:id id :type "function"
                                     :function {:name (str "yetibot_" operation)
                                                :arguments (json/generate-string args)}}]}}]})
(defn descriptor [messages]
  (->> messages (filter #(= "tool" (:role %)))
       (keep #(get-in (json/parse-string (:content %)) ["structuredContent" "datasets" 0])) last))

(def caller-adapter
  (reify adapters/Adapter
    (uuid [_] "workspace") (platform-name [_] "Slack")
    (channels [_] ["C1"]) (connected? [_] true)
    (send-msg [_ _] nil) (send-paste [_ _] nil)
    (join [_ _]) (leave [_ _]) (chat-source [_ _]) (start [_]) (stop [_])
    (connection-last-active-timestamp [_] 0) (connection-latency [_] 0)))

(defn with-current-caller [f]
  (with-redefs [adapters/adapters (atom {"workspace" caller-adapter})
                users/users (atom {{:adapter :slack :id "U1"} (:user support/request)
                                   {:adapter :slack :id "BOT"} {:id "BOT"}})]
    (binding [chat/*adapter* caller-adapter chat/*target* "C1"] (f))))

(defn chart-temps [failed-location correction?]
  (try (deref alias/loader) (catch Exception _ nil))
  (let [collected (atom []) renders (atom []) turns (atom []) sent (atom [])
        by-city (zipmap fixtures/locations fixtures/rows)
        final-result (atom nil) complete ai-command/complete-prompt]
    (with-redefs [hooks/hooks (atom @hooks/hooks)
                  hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                  channel/settings-for-chat-source (constantly {})
                  handler/add-user-message-to-history (fn [& _])
                  handler/dispatch-command-response #(swap! sent conj %)
                  history/add (fn [& _])
                  weather/current (fn [loc]
                                    (swap! collected conj loc)
                                    (if (= loc failed-location) {:error "Unavailable"}
                                      {:data [(get by-city loc)]}))
                  mcp/config (constantly {:servers [] :toolsets [toolset]})
                  ai/config (constantly {:providers [{:name "fake" :model "fake"
                                                     :url "https://synthetic.invalid/v1"}]})
                  context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                  ai-command/complete-prompt (fn [prepared]
                                               (let [result (complete prepared)]
                                                 (reset! final-result result) result))
                  kroki/client-call
                  (fn [operation request]
                    (case operation
                      "validate-render!" request
                      "render!" (do (swap! renders conj request)
                                    (public/reviewed {:result/value (:url artifact)
                                                      :result/data artifact
                                                      :result/public-data artifact}))))
                  http/post (fn [endpoint opts]
                              (let [form (:form-params opts) messages (:messages form)]
                                (swap! turns conj {:test-endpoint endpoint
                                                   :test-provider "fake" :test-model (:model form)
                                                   :max-output-tokens (:max_completion_tokens form)
                                                   :test-budget (:content (second messages))})
                                {:status 200
                                 :body (if (= 1 (count @turns))
                                         (call "collect" "command" {"command" "temps"})
                                         (let [d (descriptor messages)]
                                           (call (str "chart" (count @turns)) "chart"
                                             {"dataset_id" (get d "dataset-id") "kind" "bar"
                                              "x" "city_name" "y" (if (and correction? (= 2 (count @turns)))
                                                                     "missing" "temp")})))}))]
      (hooks/cmd-hook #"weather" #".+" weather/weather-cmd)
      (doseq [[name body] [["locs" "list edmonton 59106 nyc sunnyvale 48178"]
                          ["quicktemp" fixtures/quicktemp]
                          ["temps" "locs | xargs quicktemp | sort"]
                          ["gemma" "ai --provider fake --model fake $s"]]]
        (#'alias/wire-alias {:cmd-name name :cmd body}))
      (let [pipeline (with-current-caller #(support/run-command support/request "gemma chart temps"))]
        {:result @final-result :pipeline pipeline
         :collected @collected :renders @renders :turns @turns :sent @sent}))))

(fact "real five-location alias terminates after two plans and one render, without model links"
  (let [{:keys [result collected renders turns sent]} (chart-temps nil false)
        spec (json/parse-string (:source (first renders)) true)]
    (count turns) => 2
    (mapv :test-provider turns) => ["fake" "fake"]
    (mapv :test-model turns) => ["fake" "fake"]
    (string/includes? (:test-budget (first turns)) "total=20 admitted=0 reserved=0 remaining=20") => true
    (mapv :max-output-tokens turns) => [32768 32768]
    (mapv :test-endpoint turns) => ["https://synthetic.invalid/v1/chat/completions"
                                        "https://synthetic.invalid/v1/chat/completions"]
    (get-in result [:result/data :execution :host-budget])
    => {:limit 20 :admitted 4 :reserved 0 :remaining 16}
    (count renders) => 1
    (count (get-in spec [:data :values])) => 5
    (set (map :x (get-in spec [:data :values]))) => (set fixtures/locations)
    (get-in spec [:encoding :y :title]) => "temp (C)"
    (frequencies collected) => (zipmap fixtures/locations (repeat 1))
    (get-in result [:result/data :mcp :tool-calls]) => 2
    (get-in (public/projection result) [:result/public-data :artifacts]) => [artifact]
    (string/includes? (:result/value result) (:url artifact)) => true
    (string/includes? (:result/value result) "Discard") => false
    sent => []))

(fact "partial collection and a chart correction reuse actual data without another source call"
  (let [{:keys [result collected renders turns]} (chart-temps "nyc" true)]
    (count turns) => 3
    (get-in (json/parse-string (:source (first renders)) true) [:encoding :y :title]) => "temp (C)"
    (string/includes? (:result/value result) "4 of 5 inputs") => true
    (string/includes? (:result/value result) "nyc") => true
    (count renders) => 1
    (count (get-in (json/parse-string (:source (first renders)) true) [:data :values])) => 4
    (frequencies collected) => (zipmap fixtures/locations (repeat 1))
    (string/includes? (:result/value result) "Partial coverage") => true))
