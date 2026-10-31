(ns yetibot.test.models.ai-tools-progress
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai]
            [yetibot.api.mcp :as mcp]
            [yetibot.models.ai-tools :as tools]
            [yetibot.test.support.mcp :as support]))

(def provider {:name "local" :url "http://localhost/v1"})
(def config {:servers [{:name "search" :url "https://search.test/mcp"}]})
(def tool
  (assoc support/search-tool :session {:server (first (:servers config))}))

(defn run-fixture [cap mode callback]
  (let [turn (atom 0) calls (atom 0) observations (atom [])]
    (with-redefs
      [mcp/config (constantly (cond-> config cap (assoc :max-tool-calls cap)))
       mcp/with-tools (fn [_ f] (try (f [tool])
                                   (finally (swap! observations conj :closed))))
       mcp/call-tool (fn [& _]
                       (swap! calls inc)
                       (swap! observations conj :dispatch)
                       (case mode
                         :exception (throw (Exception. "PRIVATE-CAUSE"))
                         :unknown {:unknownOutcome true :isError true}
                         :error {:isError true}
                         {:content [{:type "text" :text "PRIVATE-RESULT"}]}))
       ai/chat
       (fn [_ _ _ opts]
         (let [n (swap! turn inc)
               name (get-in opts [:tools 0 :function :name])
               call (fn [i]
                      {:id (str "PRIVATE-ID-" n "-" i) :type "function"
                       :function {:name name :arguments
                                  (json/generate-string
                                    {:query (str "PRIVATE-QUERY-" n "-" i)})}})]
           (if (or (= n 1) (and (= mode :turns) (= n 2)))
             {:choices [{:message {:tool_calls
                                  (if (#{:reject :exception :unknown} mode)
                                    [(call 1) (call 2)] [(call 1)])}}]}
             {:choices [{:message {:content "Done"}}]})))]
      (let [result (try
                     (tools/complete provider "chat"
                                     [{:role "user" :content "PRIVATE-PROMPT"}]
                                     {:on-progress
                                      (fn [event]
                                        (swap! observations conj event)
                                        (callback event))})
                     (catch Exception e (:ai/tool-failure (ex-data e))))]
        {:result result :calls @calls :observations @observations}))))

(fact "ai-tools progress uses shared ordinals and actual attempt accounting"
  (let [{:keys [result calls observations]} (run-fixture nil :turns identity)
        events (vec (keep :tool-event observations))
        budgets (vec (keep :tool-budget observations))]
    (:result/value result) => "Done"
    calls => 2
    (mapv :call events) => [1 1 2 2]
    (mapv :outcome events) => [:running :success :running :success]
    (mapv :label events) => (vec (repeat 4 "mcp_0_search__web_search"))
    (first budgets) => {:used 0 :limit 6 :remaining 6}
    (last budgets) => {:used 2 :limit 6 :remaining 4}
    (some #(re-find #"PRIVATE" (pr-str %)) (filter map? observations)) => nil
    (last observations) => :closed))

(facts "ai-tools progress rejected batches never run and lower cap stays real"
  (let [{:keys [calls observations result]} (run-fixture 1 :reject identity)]
    calls => 0
    (:reason result) => :budget-exceeded
    (mapv :outcome (keep :tool-event observations)) => [:rejected :rejected]
    (every? #(= {:limit 1 :used 0 :remaining 1} %)
            (keep :tool-budget observations)) => true))

(facts "ai-tools progress distinguishes errors, unknowns and skipped calls"
  (doseq [[mode outcomes] [[:error [:running :error]]
                          [:unknown [:running :unknown :skipped]]
                          [:exception [:running :unknown :skipped]]]]
    (let [{:keys [calls observations]} (run-fixture nil mode identity)]
      (fact calls => 1)
      (fact (mapv :outcome (keep :tool-event observations)) => outcomes)
      (fact (last (keep :tool-budget observations))
            => {:used 1 :limit 6 :remaining 5}))))

(fact "ai-tools throwing progress cannot change dispatch or cleanup"
  (let [normal (run-fixture nil :turns identity)
        throwing (run-fixture nil :turns
                              #(throw (Exception. (str "PRIVATE" %))))]
    (:result throwing) => (:result normal)
    (:calls throwing) => 2
    (last (:observations throwing)) => :closed))

(facts "ai-tools progress reuses trace label privacy before any public bound"
  (doseq [name ["PRIVATE_PROMPT" "s-ecret" "quote\"secret"
                "ns/s-ecret" "amount$123456" "@everyone\nPRIVATE"]]
    (let [seen (atom []) turn (atom 0)
          advertised (assoc tool :name name)]
      (with-redefs
        [mcp/config (constantly (assoc config :secrets
                                      ["s-ecret" "quote\"secret"]))
         mcp/with-tools (fn [_ f] (f [advertised]))
         mcp/call-tool (fn [& _] {:content []})
         ai/chat (fn [_ _ _ opts]
                   (if (= 1 (swap! turn inc))
                     {:choices [{:message
                                 {:tool_calls
                                  [{:id "PRIVATE-ID" :type "function"
                                    :function
                                    {:name (get-in opts
                                                   [:tools 0 :function :name])
                                     :arguments "{}"}}]}}]}
                     {:choices [{:message {:content "Done"}}]}))]
        (tools/complete provider "chat"
                        [{:role "user" :content "PRIVATE_PROMPT"}]
                        {:on-progress #(swap! seen conj %)})
        (fact (some #(re-find #"PRIVATE|s-ecret|quote|123456|@everyone"
                              (pr-str %)) @seen) => nil)
        (fact (every? #(<= (count (:label %)) 64)
                      (keep :tool-event @seen)) => true)))))

(fact "configured explicit secrets retain literal and escaped redaction"
  (let [secret "quote\"secret" seen (atom []) turn (atom 0)
        encoded (json/generate-string secret)
        escaped (subs encoded 1 (dec (count encoded)))]
    (with-redefs
      [mcp/config (constantly (assoc config :secrets [secret]))
       mcp/with-tools (fn [_ f] (f [tool]))
       mcp/call-tool (fn [& _]
                       {:isError true :content [{:type "text" :text secret}]})
       ai/chat
       (fn [_ _ msgs opts]
         (if (= 1 (swap! turn inc))
           {:choices [{:message
                       {:tool_calls
                        [{:id "PRIVATE-ID" :type "function"
                          :function
                          {:name (get-in opts [:tools 0 :function :name])
                           :arguments "{}"}}]}}]}
           (do
             (fact (string/includes? (:content (last msgs)) "quote") => false)
             {:choices [{:message {:content (str "Benign " secret)}}]})))]
      (:result/value
        (tools/complete provider "chat" []
                        {:on-progress #(swap! seen conj %)}))
      => "Benign [redacted]"
      (some #(string/includes? (pr-str %) escaped) @seen) => nil
      (some #(string/includes? (pr-str %) "quote") @seen) => nil)))

(fact "tool-loop response ordinals are private host-assigned progress config"
  (let [responses (atom []) turn (atom 0)]
    (with-redefs
      [mcp/config (constantly config)
       mcp/with-tools (fn [_ f] (f [tool]))
       mcp/call-tool (fn [& _] {:content []})
       ai/chat
       (fn [_ _ _ opts]
         (swap! responses conj (get-in opts [:progress-config
                                            :progress-response]))
         (if (= 1 (swap! turn inc))
           {:choices [{:message
                       {:tool_calls
                        [{:id "PRIVATE-ID" :type "function"
                          :function
                          {:name (get-in opts [:tools 0 :function :name])
                           :arguments "{}"}}]}}]}
           {:choices [{:message {:content "Done"}}]}))]
      (:result/value (tools/complete provider "chat" []
                                    {:on-progress identity})) => "Done"
      @responses => [1 2])))

(fact "real tool-loop stream progress isolates repeated native IDs per response"
  (let [seen (atom []) requests (atom []) calls (atom 0)]
    (with-redefs
      [mcp/config (constantly config)
       mcp/with-tools (fn [_ f] (f [tool]))
       mcp/call-tool (fn [& _] (swap! calls inc) {:content []})
       clj-http.client/post
       (fn [_ options]
         (swap! requests conj (:form-params options))
         (let [first? (= 1 (count @requests))
               name (some #(when (= "function" (:type %)) (:name %))
                          (get-in options [:form-params :tools]))
               native {:id "PRIVATE-NATIVE-ID" :type "web_search_call"
                       :status "completed" :action {:query "PRIVATE"}}
               output (if first?
                        [native {:type "function_call" :call_id "PRIVATE-CALL"
                                 :name name :arguments "{}"}]
                        [native {:type "message" :role "assistant"
                                 :content [{:type "output_text"
                                            :text "Done"}]}])]
           {:status 200 :headers {"content-type" "application/json"}
            :body (java.io.ByteArrayInputStream.
                    (.getBytes
                      (json/generate-string {:status "completed"
                                             :output output}) "UTF-8"))}))]
      (:result/value
        (tools/complete (assoc provider :api "responses"
                                       :native-tools ["web_search"])
                        "chat" [] {:on-progress #(swap! seen conj %)}))
      => "Done"
      @calls => 1
      (mapv (juxt :response :call :outcome)
            (filter #(= :native (:domain %)) (keep :tool-event @seen)))
      => [[1 1 :success] [2 1 :success]]
      (last (keep :tool-budget @seen)) => {:limit 6 :used 1 :remaining 5}
      (some #(contains? % :progress-response) @requests) => nil
      (some #(re-find #"PRIVATE" (pr-str %)) @seen) => nil)))
