(ns yetibot.test.integration.ai-command-charts
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.spec.alpha :as s]
            [clj-http.client :as http]
            [midje.sweet :refer :all]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.commands.weather :as weather]
            [yetibot.commands.ai :as ai-command]
            [yetibot.models.ai-context :as context]
            [yetibot.models.ai-datasets :as datasets]
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

(defn chart-temps
  ([failed-location correction?] (chart-temps failed-location correction? {}))
  ([failed-location correction? {:keys [alias-depth field imperial?] :or {alias-depth 0 field "temp"}}]
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
                                     (if (if (set? failed-location) (contains? failed-location loc) (= loc failed-location)) {:error "Unavailable"}
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
                                                    :test-budget (:content (second messages))
                                                    :dataset (descriptor messages)})
                                 {:status 200
                                  :body (if (= 1 (count @turns))
                                          (call "collect" "command" {"command" "temps"})
                                          (let [d (descriptor messages)]
                                            (call (str "chart" (count @turns)) "chart"
                                              {"dataset_id" (get d "dataset-id") "kind" "bar"
                                               "x" "city_name" "y" (if (and correction? (= 2 (count @turns)))
                                                                      "missing" field)})))}))]
       (hooks/cmd-hook #"weather" #".+" weather/weather-cmd)
       (doseq [i (range alias-depth)]
         (#'alias/wire-alias {:cmd-name (str "nested-weather-" i)
                             :cmd (str (if (= (inc i) alias-depth) "weather" (str "nested-weather-" (inc i))) " $s")}))
       (doseq [[name body] [["locs" "list edmonton 59106 nyc sunnyvale 48178"]
                           ["quicktemp" (if (pos? alias-depth)
                                          (string/replace-first fixtures/quicktemp "weather" "nested-weather-0")
                                          (if imperial? (string/replace-first fixtures/quicktemp "weather" "weather -f")
                                            fixtures/quicktemp))]
                           ["temps" "locs | xargs quicktemp | sort"]
                           ["gemma" "ai --provider fake --model fake $s"]]]
         (#'alias/wire-alias {:cmd-name name :cmd body}))
       (let [pipeline (with-current-caller #(support/run-command support/request "gemma chart temps"))]
         {:result @final-result :pipeline pipeline
          :collected @collected :renders @renders :turns @turns :sent @sent})))))

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

(defn private-chart [rows pipeline? providers]
  (fact (s/valid? :yetibot.api.ai/settings {:providers providers}) => true)
  (let [turns (atom []) renders (atom []) raw (atom nil)
        lookup datasets/lookup! complete ai-command/complete-prompt
        resolve-provider ai/resolve-provider resolved (atom [])]
    (with-redefs [hooks/hooks (atom @hooks/hooks)
                  hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                  channel/settings-for-chat-source (constantly {})
                  history/add (fn [& _])
                  mcp/config (constantly {:servers [] :toolsets [toolset]})
                  ai/config (constantly {:providers providers})
                  ai/resolve-provider (fn [p] (swap! resolved conj (:name p)) (resolve-provider p))
                  context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                  datasets/lookup! (fn [& args] (let [d (apply lookup args)] (reset! raw d) d))
                  kroki/client-call (fn [operation request]
                                      (when (= operation "render!") (swap! renders conj request))
                                      (if (= operation "validate-render!") request
                                        (public/reviewed {:result/value (:url artifact)
                                                          :result/data artifact
                                                          :result/public-data artifact})))
                  http/post (fn [_ opts]
                              (let [messages (get-in opts [:form-params :messages])
                                    d (or (descriptor messages)
                                          (some (fn [m] (try (get (json/parse-string (:content m)) "dataset")
                                                            (catch Exception _ nil))) messages))]
                                (swap! turns conj messages)
                                {:status 200 :body
                                 (cond
                                   (and (not pipeline?) (= 1 (count @turns)))
                                   (call "private-collect" "command" {"command" "private-rows"})
                                   (<= (count @turns) (if pipeline? 1 2))
                                   (call "private-chart" "chart" {"dataset_id" (get d "dataset-id")
                                                                "kind" "point" "x" "label" "y" "reading"})
                                   :else {:choices [{:message {:role "assistant" :content "Chart rejected safely."}}]})}))]
      (hooks/cmd-hook-resolved #"private-rows" #".*"
        (fn [_] {:result/value "private rows" :result/data rows}))
      (let [result (support/in-registered-event support/request "ai chart private rows"
                     (fn [event]
                       (complete (ai-command/prepare-prompt
                                   (cond-> (assoc event :match "chart private rows")
                                     pipeline? (assoc :data rows :raw "private rows"))))))]
        {:result result :turns @turns :renders @renders :raw @raw :resolved @resolved}))))

(fact "complete supplied datasets redact sensitive sibling and cross-row copies before planning and reject renderer IO"
  (doseq [secret ["UNCONFIGURED-PRIVATE" "private-\"quote\\slash" 987654321]
          key [:auth/token "auth/api-key"]]
    (let [rows (conj (vec (repeat 5 {:label secret :reading (if (number? secret) secret 2)}))
                     {:label "last" :reading 3 key secret})
          r (private-chart rows true [{:name "fake" :model "fake" :url "https://synthetic.invalid/v1"}])
          encoded (json/generate-string secret)
          spelling (if (string? secret) (subs encoded 1 (dec (count encoded))) encoded)]
      (some (fn [m] (when (string/includes? (:content m) spelling) true))
            (mapcat identity (:turns r))) => nil
      (:renders r) => []
      (:rows (:raw r)) => (json/parse-string (json/generate-string rows))
      (string/includes? (str (:turns r)) "credential-input") => true)))

(fact "active A command capture preserves configured B literal credentials without resolving B key files"
  (let [secret "SYNTHETIC-PROVIDER-B-KEY"
        rows [{:label secret :reading 2}]
        r (private-chart rows false
            [{:name "fake" :model "fake" :key "SYNTHETIC-PROVIDER-A-KEY" :url "https://synthetic.invalid/v1"}
             {:name "other" :model "other" :key secret :url "https://other.invalid/v1"}
             {:name "unrelated-file" :model "other" :key-file "/must-not-open-unrelated-provider" :url "https://file.invalid/v1"}])]
    (some (fn [m] (when (string/includes? (:content m) secret) true))
          (mapcat identity (:turns r))) => nil
    (:renders r) => []
    (:rows (:raw r)) => [{"label" secret "reading" 2}]
    (every? #{"fake"} (:resolved r)) => true
    (seq (:resolved r)) => truthy
    (string/includes? (str (:turns r)) "credential-input") => true))

(fact "real nested alias failures count each failed weather input once independent of depth"
  (doseq [depth [1 2 3]
          failed [#{"nyc"} #{"nyc" "edmonton"}]]
    (let [{:keys [result turns renders collected]} (chart-temps failed false {:alias-depth depth})
          coverage (get-in turns [1 :dataset "coverage"])
          succeeded (- 5 (count failed))]
      (select-keys coverage ["requested" "succeeded" "failed" "status"])
      => {"requested" 5 "succeeded" succeeded "failed" (count failed) "status" "partial"}
      (string/includes? (:result/value result) (str succeeded " of 5 inputs")) => true
      (count renders) => 1
      (frequencies collected) => (zipmap fixtures/locations (repeat 1)))))

(fact "actual metric and imperial-display weather companions carry temperature wind and humidity units into axes"
  (doseq [imperial? [false true]
          [field unit value] [["temp" "C" nil] ["app_temp" "C" nil]
                              ["wind_spd" "km/h" 5] ["rh" "%" 60]]]
    (let [{:keys [turns renders collected]} (chart-temps nil false {:field field :imperial? imperial?})
          d (get-in turns [1 :dataset])
          fields (into {} (map (juxt #(get % "name") #(get % "unit")) (get d "fields")))
          spec (json/parse-string (:source (first renders)) true)]
      fields => (contains {"temp" "C" "app_temp" "C" "wind_spd" "km/h" "rh" "%"})
      (get-in spec [:encoding :y :title]) => (str (string/replace field "_" " ") " (" unit ")")
      (mapv :y (get-in spec [:data :values])) => (if value (vec (repeat 5 value)) [-1.0 3.0 10.0 13.0 18.0])
      (get d "source") => "weather"
      (string? (get d "captured-at")) => true
      (get-in d ["coverage" "requested"]) => 5
      (get-in d ["coverage" "status"]) => "complete"
      (frequencies collected) => (zipmap fixtures/locations (repeat 1)))))

(defn request-private-datasets [secret order raw-render?]
  (let [turns (atom []) renders (atom []) retained (atom [])
        retain datasets/retain! complete ai-command/complete-prompt
        rows-a [{:label "safe" :reading 1 :auth/token secret}]
        rows-b [{:label secret :reading (if (number? secret) secret 2)}]
        command-call (fn [which] (get-in (call (str "collect-" which "-987654321") "command"
                                               {"command" (str "request-private-" which)})
                                        [:choices 0 :message :tool_calls 0]))
        sequence (if (= order :a-first) ["a" "b"] ["b" "a"])]
    (with-redefs [hooks/hooks (atom @hooks/hooks)
                  hooks/re-prefix->topic (atom @hooks/re-prefix->topic)
                  channel/settings-for-chat-source (constantly {})
                  history/add (fn [& _])
                  mcp/config (constantly {:servers [] :toolsets [(update toolset :native-tools conj "kroki_render")]})
                  ai/config (constantly {:providers [{:name "fake" :model "fake" :url "https://synthetic.invalid/v1"}]})
                  context/messages (fn [_ prompt] [{:role "user" :content prompt}])
                  datasets/retain! (fn [store owner envelope]
                                     (let [d (retain store owner envelope)]
                                       (swap! retained conj {:id (:dataset-id d)
                                                            :raw (datasets/lookup! store owner (:dataset-id d))}) d))
                  kroki/available-tools (constantly ["kroki_render"])
                  kroki/client-call (fn [operation request]
                                      (if (= operation "validate-render!") request
                                        (do (swap! renders conj request)
                                            (public/reviewed {:result/value (:url artifact)
                                                              :result/data artifact :result/public-data artifact}))))
                  http/post (fn [_ opts]
                              (let [messages (get-in opts [:form-params :messages])
                                    n (count (swap! turns conj messages))
                                    collect-turns (if (= order :batch) 1 2)]
                                {:status 200 :body
                                 (cond
                                   (<= n collect-turns)
                                   {:choices [{:message {:role "assistant" :tool_calls
                                                        (if (= order :batch)
                                                          [(command-call "b") (command-call "a")]
                                                          [(command-call (nth sequence (dec n)))])}}]}
                                   (= n (inc collect-turns))
                                   (if raw-render?
                                     (call "raw-987654321" "kroki_render"
                                           {"type" "mermaid" "source" (str "graph TD; "
                                                                          (if (string? secret)
                                                                            (str (format "\\u%04x" (int (first secret))) (subs secret 1))
                                                                            secret) "-->B")})
                                     (let [b (first (filter #(= (json/parse-string (json/generate-string rows-b))
                                                               (:rows (:raw %))) @retained))]
                                       (call "chart-987654321" "chart"
                                             {"dataset_id" (:id b) "kind" "point" "x" "label" "y" "reading"})))
                                   :else {:choices [{:message {:role "assistant" :content "Rejected safely."}}]})}))]
      (doseq [[suffix rows] [["a" rows-a] ["b" rows-b]]]
        (hooks/cmd-hook-resolved (re-pattern (str "request-private-" suffix)) #".*"
          (fn [_] {:result/value "private structured rows" :result/data rows})))
      (let [result (try (with-current-caller
                         #(support/in-registered-event support/request "ai chart request private rows"
                            (fn [event] (complete (ai-command/prepare-prompt (assoc event :match "chart request private rows"))))))
                        (catch clojure.lang.ExceptionInfo e {:error (ex-data e)}))]
        {:result result :turns @turns :renders @renders :retained @retained
         :expected-rows [rows-a rows-b]}))))

(fact "real request command loop keeps cross-dataset secrets out of later inference and both chart and raw renderer"
  (doseq [secret ["REQUEST-PRIVATE" "private-\"quote\\slash" 987654321 1]
          order [:a-first :b-first :batch]
          raw-render? [false true]]
    (let [r (request-private-datasets secret order raw-render?)
          after-learning (drop (if (= order :batch) 1 2) (:turns r))
          encoded (json/generate-string secret)
          spelling (if (string? secret) (subs encoded 1 (dec (count encoded))) encoded)]
      (count (:retained r)) => 2
      (count after-learning) => pos?
      (some (fn [message]
              (let [content (or (:content message) "")
                    ;; Genuine host handles deliberately survive even a one-digit
                    ;; sensitive value; inspect untrusted payloads separately.
                    content (if (= "tool" (:role message))
                              (json/generate-string
                                (update-in (json/parse-string content true) [:structuredContent :datasets]
                                           #(mapv (fn [d] (dissoc d :dataset-id)) %)))
                              content)]
                (when (string/includes? content spelling) true)))
            (mapcat identity after-learning)) => nil
      (:renders r) => []
      (set (map (comp :rows :raw) (:retained r)))
      => (set (json/parse-string (json/generate-string (:expected-rows r))))
      (every? #(try (java.util.UUID/fromString (:id %)) true (catch Exception _ false)) (:retained r)) => true
      (set (keep :tool_call_id (first after-learning))) => #{"collect-a-987654321" "collect-b-987654321"}
      (set (mapcat (fn [m]
                     (when (= "tool" (:role m))
                       (map :dataset-id (get-in (json/parse-string (:content m) true)
                                               [:structuredContent :datasets]))))
                   (first after-learning))) => (set (map :id (:retained r)))
      (if raw-render?
        (get-in r [:result :error :ai/tool-failure :code])
        (when (string/includes? (str (:turns r)) "credential-input") :credential-input))
      => :credential-input)))
