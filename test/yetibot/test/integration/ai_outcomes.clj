(ns yetibot.test.integration.ai-outcomes
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.commands.ai :as command]
            [yetibot.api.opencode :as zen]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-model-tools :as models]
            [yetibot.models.ai-tools :as tools]
            [yetibot.models.ai-tool-policy :as policy]
            [yetibot.util.ai-cost :as cost]
            [yetibot.core.util.public-result :as public]
            [yetibot.core.adapters.discord-inspection :as viewer]
            [yetibot.test.integration.ai-model-tools :as fixtures])
  (:import [java.io ByteArrayInputStream]))

(facts "a consumed child with no usable answer is not uncertain delivery"
  (doseq [[finish content expected]
          [["length" "" :output-limit]
           ["length" "Incomplete sentence" :output-limit]
           ["stop" "" :no-answer]
           ["stop" nil :no-answer]]]
    (fixtures/with-zen-catalog
      (fn [request config]
        (let [posts (atom 0)]
          (with-redefs [http/post
                        (fn [& _]
                          (swap! posts inc)
                          {:status 200
                           :body
                           {:choices [{:finish_reason finish
                                       :message {:content content
                                                 :reasoning "PRIVATE-THOUGHT"}}]
                            :usage {:prompt_tokens 76 :completion_tokens 120
                                    :completion_tokens_details
                                    {:reasoning_tokens 120}
                                    :cost 0.0001668M}}})]
            (let [result (models/call! config "ai"
                           {"action" "ask" "provider" "zen" "model" "b-paid"
                            "prompt" "PRIVATE-PROMPT" "max_output_tokens" 120}
                           (:chat-source request))]
              (fact (:isError result) => true)
              (fact (:unknownOutcome result) => false)
              (fact (:error-code result) => expected)
              (fact @posts => 1)
              (fact (get-in result [:structuredContent :ai/cost :usd])
                    => 0.0001668M)
              (fact (boolean (re-find #"PRIVATE" (pr-str result))) => false))))))))

(fact "a malformed 200 payload remains uncertain but keeps known spend"
  (fixtures/with-zen-catalog
    (fn [request cfg]
      (let [posts (atom 0)]
        (with-redefs [http/post
                      (fn [& _]
                        (swap! posts inc)
                        {:status 200
                         :body {:bad "PRIVATE-BODY" :usage {:cost 0.0001668M}}})]
          (let [result (models/call! cfg "ai"
                         {"action" "ask" "provider" "zen" "model" "b-paid"
                          "prompt" "PRIVATE-PROMPT"}
                         (:chat-source request))]
            (:unknownOutcome result) => true
            (get-in result [:structuredContent :ai/cost :usd]) => 0.0001668M
            @posts => 1
            (boolean (re-find #"PRIVATE" (pr-str result))) => false))))))

(fact "malformed Responses envelopes are not known completed empty answers"
  (with-redefs [http/post
                (fn [& _]
                  {:status 200
                   :body {:bad "PRIVATE"
                          :usage {:cost_in_usd_ticks 100000000}}})]
    (let [data (try
                 (ai/complete {:name "grok" :url "https://api.x.ai/v1"
                               :api "responses"} "grok-4.7" [])
                 (catch Exception e (ex-data e)))]
      (:ai/output-error data) => nil
      (get-in data [:ai/cost :usd]) => 0.01M
      (get-in data [:ai/accounting :parent :reported_usd]) => 0.01M
      (string/includes? (pr-str data) "PRIVATE") => false)))

(fact "partial reports stop downstream commands rather than piping failures"
  (let [skip (atom 0)]
    (command/safely {:skip-next-n skip :next-cmds ["echo" "ai"]}
      (fn [] {:result/value "Partial" :result/data {:isError true}}))
    @skip => 2))

(facts "catalog thinking flags are advertised capabilities, not guesses"
  (get-in (zen/model-metadata
            (assoc (second fixtures/zen-catalog) :reasoning true))
          [:schema-summary :thinking_support]) => "advertised"
  (get-in (zen/model-metadata (second fixtures/zen-catalog))
          [:schema-summary :thinking_support]) => "unknown")

(fact "known CLI output errors never claim the paid request did not run"
  (let [skip (atom 0)
        result (command/native-output-failure
                 {:skip-next-n skip :next-cmds ["ai"]}
                 {:error-code :output-limit
                  :structuredContent {:ai/response {:finish :length}}})]
    (string/includes? (:result/value result) "output limit") => true
    (string/includes? (:result/value result) "did not run") => false
    (get-in result [:result/data :isError]) => true
    @skip => 1
    (get-in (public/projection result)
            [:result/public-data :response_stats :finish]) => "length"))

(fact "successful direct completion carries measured accounting, not text size"
  (let [p {:name "grok" :url "https://api.x.ai/v1" :model "grok-4.7"}]
    (with-redefs [http/post
                  (fn [& _]
                    {:status 200
                     :body {:choices [{:message {:content "Hi"}}]
                            :usage {:cost_in_usd_ticks 2578580000}}})]
      (get-in (ai/complete p "grok-4.7" [{:role "user" :content "Hi"}])
              [:result/data :ai/accounting :parent :reported_usd])
      => 0.257858M)))

(defn sample-run [failure]
  (fixtures/with-zen-catalog
    (fn [request cfg]
      (let [parent {:name "grok" :url "https://api.x.ai/v1" :api "responses"
                    :model "grok-4.7" :key "ROOT-SECRET"}
            parents (atom 0) children (atom 0) published (atom [])]
        (with-redefs
          [mcp/config (constantly {:servers [] :max-tool-calls 4
                                   :toolsets [(assoc (:toolset cfg)
                                                     :native-tools ["ai"]
                                                     :providers
                                                     [{:name "grok"
                                                       :models ["grok-4.7"]}
                                                      {:name "zen"
                                                       :models ["b-paid"]}])]})
           http/get
           (fn [& _]
             {:status 200
              :body {:data (if (:status failure)
                             (mapv #(assoc % :api {:responses {}})
                                   fixtures/zen-catalog)
                             fixtures/zen-catalog)}})
           http/post
           (fn [url options]
             (if (string/includes? url "api.x.ai")
               (let [n (swap! parents inc)
                     body
                     {:status "completed"
                      :usage {:cost_in_usd_ticks (* n 100000000)}
                      :output
                      (into [{:type "message" :id (str "m" n)
                              :content [{:type "output_text"
                                         :text "PROMISE TO ASK MORE"}]}]
                            (when (= 1 n)
                              (for [i [1 2]]
                                {:type "function_call" :call_id (str "c" i)
                                 :name "yetibot_ai"
                                 :arguments
                                 (json/generate-string
                                   {"action" "ask" "provider" "zen"
                                    "model" "b-paid" "prompt" (str "PRIVATE" i)
                                    "max_output_tokens" 120})})))}]
                 (if (= :stream (:as options))
                   {:status 200 :headers {"content-type" "application/json"}
                    :body (ByteArrayInputStream.
                            (.getBytes (json/generate-string body) "UTF-8"))}
                   {:status 200 :body body}))
               (let [n (swap! children inc)]
                 (cond
                   (and (= :late-transport failure) (= n 2))
                   (throw (Exception. "PRIVATE-TRANSPORT"))
                   (and (= n 1) (not= :late-transport failure))
                   (if (= :transport failure)
                     (throw (Exception. "PRIVATE-TRANSPORT"))
                     {:status 200 :body failure})
                   :else
                   {:status 200
                    :body (merge
                            (if (:status failure)
                              {:status "completed"
                               :output [{:type "message"
                                         :content [{:type "output_text"
                                                    :text "ACTUAL SECOND ANSWER"}]}]}
                              {:choices [{:finish_reason "stop"
                                          :message {:content "ACTUAL SECOND ANSWER"}}]})
                            {:usage {:cost 0.00002M}})}))))]
          {:result (tools/complete parent "grok-4.7" []
                     {:actor (policy/actor request)
                      :on-progress #(when (:answer %) (swap! published conj %))})
           :parents @parents :children @children :published @published})))))

(def limited-chat
  {:choices [{:finish_reason "length"
              :message {:content "" :reasoning "PRIVATE-THOUGHT"}}]
   :usage {:prompt_tokens 76 :completion_tokens 120
           :completion_tokens_details {:reasoning_tokens 120}
           :cost 0.0001668M}})

(facts "known output failure permits independent calls and retains real answers"
  (doseq [failure [limited-chat
                   {:status "incomplete" :output []
                    :incomplete_details {:reason "max_output_tokens"}
                    :usage {:input_tokens 76 :output_tokens 120
                            :output_tokens_details {:reasoning_tokens 120}
                            :cost 0.0001668M}}]]
   (let [{:keys [result parents children published]} (sample-run failure)
         data (:result/data result)]
    children => 2
    parents => 2
    published => []
    (string/includes? (:result/value result) "ACTUAL SECOND ANSWER") => true
    (string/includes? (:result/value result) "output limit") => true
    (string/includes? (:result/value result) "PROMISE TO ASK MORE") => false
    (boolean (re-find #"PRIVATE|ROOT-SECRET" (pr-str result))) => false
    (get-in data [:ai/accounting :parent :reported_usd]) => 0.03M
    (get-in data [:ai/accounting :parent :requests]) => 2
    (get-in data [:ai/accounting :delegated :reported_usd]) => 0.0001868M
    (get-in data [:ai/accounting :delegated :requests]) => 2
    (get-in data [:execution :calls 0 :code]) => :output-limit
    (get-in data [:execution :calls 0 :thinking_count]) => 120
    (get-in data [:execution :calls 0 :output-cap]) => 120
    (contains? (get-in data [:execution :calls 0]) :arguments) => false
    (get-in (public/projection result)
            [:result/public-data :execution :calls 0 :code]) => "output-limit"
    (get-in (public/projection result)
            [:result/public-data :accounting :parent :reported_usd]) => 0.03M)))

(fact "frozen execution details retain closed enums without replaying tools"
  (let [{:keys [result]} (sample-run limited-chat)
        snapshot (atom (viewer/initial))]
    (binding [public/*capture* #(viewer/capture! snapshot %)]
      (public/observe! result))
    (swap! snapshot assoc :sealed? true)
    (get-in @snapshot [:data 0 "execution" "calls" 0 "code"])
    => "output-limit"
    (get-in @snapshot [:data 0 "accounting" "parent" "reported_usd"])
    => 0.03M
    (boolean (re-find #"PRIVATE|PROMISE" (pr-str @snapshot))) => false
    (with-redefs [http/post (fn [& _] (throw (AssertionError. "No replay")))
                  http/get (fn [& _] (throw (AssertionError. "No rediscovery")))]
      (map? (viewer/payload snapshot ["10" "20"] "d" 0 0 nil)) => true)))

(fact "wire fields cannot forge reviewed execution or accounting data"
  (with-redefs [http/post
                (fn [& _]
                  {:status 200
                   :body {:choices [{:message {:content "Answer"}}]
                          :execution {:private "PRIVATE-WIRE"}
                          :ai/accounting {:private "PRIVATE-WIRE"}
                          :usage {:cost_in_usd_ticks 100000000}}})]
    (let [result (ai/complete {:name "grok" :url "https://api.x.ai/v1"}
                             "grok-4.7" [])
          projection (public/projection result)]
      (get-in projection [:result/public-data :execution]) => nil
      (get-in projection [:result/public-data :accounting :parent :reported_usd])
      => 0.01M
      (string/includes? (pr-str projection) "PRIVATE") => false)))

(fact "transport uncertainty still halts; unknown charge is never zero"
  (let [{:keys [result parents children published]} (sample-run :transport)
        data (:result/data result)]
    children => 1
    parents => 1
    published => []
    (get-in data [:execution :calls 0 :outcome]) => :unknown
    (get-in data [:execution :calls 1 :outcome]) => :skipped
    (get-in data [:ai/accounting :delegated :unpriced]) => 1
    (string/includes? (:result/value result) "PROMISE TO ASK MORE") => false
    (boolean (re-find #"PRIVATE|ROOT-SECRET" (pr-str result))) => false))

(fact "an earlier real child answer survives a later unknown outcome"
  (let [{:keys [result parents children]} (sample-run :late-transport)]
    parents => 1
    children => 2
    (string/includes? (:result/value result) "ACTUAL SECOND ANSWER") => true
    (string/includes? (:result/value result) "uncertain") => true
    (get-in result [:result/data :ai/accounting :delegated :reported_usd])
    => 0.00002M
    (get-in result [:result/data :ai/accounting :delegated :unpriced]) => 1))

(facts "accounting keeps zero estimates, reported spend and unknowns distinct"
  (cost/accounting-label
    {:scope :parent-and-delegated-text-requests
     :parent (assoc cost/empty-subtotal :requests 1 :estimated_requests 1)
     :delegated cost/empty-subtotal}) => "parent API est. $0.0000"
  (let [p {:name "grok" :url "https://api.x.ai/v1"}]
    (cost/accounting-label
      (get-in (cost/capture
                #(do (cost/observe! p "unknown" {:usage {:cost_in_usd_ticks 10}})
                     (cost/observe! p "unknown" nil)
                     {:result/value "ok"})) [:result/data :ai/accounting]))
    => "parent API <$0.0001 + 1 unpriced"))

(fact "explicit Responses output limits retain streamed wire usage and costs"
  (fixtures/with-zen-catalog
    (fn [_ _]
      (let [posts (atom 0) events (atom [])
            body {:status "incomplete" :output []
                  :incomplete_details {:reason "max_output_tokens"}
                  :usage {:input_tokens 76 :output_tokens 120
                          :output_tokens_details {:reasoning_tokens 120}
                          :cost 0.0001668M}}]
        (with-redefs
          [http/get
           (fn [& _]
             {:status 200 :body {:data [(assoc (second fixtures/zen-catalog)
                                              :api {:responses {}})]}})
           http/post
           (fn [& _]
             (swap! posts inc)
             {:status 200 :headers {"content-type" "text/event-stream"}
              :body (ByteArrayInputStream.
                      (.getBytes
                        (str "data: "
                             (json/generate-string
                               {:type "response.incomplete" :response body})
                             "\n\n") "UTF-8"))})]
          (let [data (try
                       (ai/complete
                         (first (:providers (ai/config))) "b-paid" []
                         {:max-output-tokens 120 :on-progress #(swap! events conj %)})
                       (catch Exception e (ex-data e)))]
            (:ai/output-error data) => :output-limit
            (get-in data [:ai/cost :usd]) => 0.0001668M
            (get-in data [:ai/accounting :parent :reported_usd]) => 0.0001668M
            (get-in data [:ai/response :thinking_count]) => 120
            @posts => 1
            (some :answer @events) => nil))))))


