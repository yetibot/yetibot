(ns yetibot.test.api.ai-diagnostics
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [taoensso.timbre :as log]
            [yetibot.api.ai :as ai]
            [yetibot.api.ai-diagnostics :as diagnostics])
  (:import [java.io ByteArrayInputStream IOException InputStream]
           [java.net SocketTimeoutException]
           [java.util.concurrent CountDownLatch ScheduledThreadPoolExecutor
            TimeUnit]
           [org.apache.http.concurrent Cancellable]
           [org.apache.http.client.methods HttpGet]))

(def sentinel "SECRET-SENTINEL account balance USD 123456.78")
(def provider {:name sentinel :model sentinel :url "https://example.test/v1"})
(def prompt [{:role "user" :content sentinel}])

(defn failure [f]
  (try (f) nil (catch Exception e e)))

(defn capture [f]
  (let [logs (atom [])]
    (log/with-config
      (assoc log/*config* :level :trace :middleware []
             :appenders
             {:capture {:enabled? true :async? false
                        :fn #(swap! logs conj (:vargs %))}})
      (let [result (f)] [result @logs]))))

(fact "HTTP400 captures only finite categories, not arbitrary error text"
  (let [[error logs]
        (capture
          #(with-redefs
             [http/post
              (fn [& _]
                {:status 400
                 :body {:error {:message sentinel :type "invalid_request_error"
                                :code "invalid_function_parameters"
                                :param "tools[0].function.parameters"}
                        :model sentinel :provider sentinel}})]
             (failure
               (fn []
                 (ai/chat provider sentinel prompt
                          {::diagnostics/stage :follow-up
                           :tools [{:type "function"
                                    :function {:name sentinel}}]
                           :tool_choice "auto"})))))]
    (ex-data error)
    => {:ai/error true
        :ai/diagnostic
        {:stage :follow-up :endpoint :chat-completions :stream? false
         :provider :other :model :other :tool-count 1 :item-count 1
         :tool-choice :auto :item-types [:message] :opaque-state? false
         :failure :http :status 400 :error-class :invalid-request
         :error-code :invalid-tool-schema :parameter :tools}}
    (count logs) => 1
    (first logs) => ["ai-provider-diagnostic" (:ai/diagnostic (ex-data error))]
    (string/includes? (pr-str [(ex-data error) logs]) sentinel) => false
    (.getMessage error)
    => (str "AI provider " sentinel " returned HTTP 400.")))

(facts "unknown, huge and nested fields never become diagnostic values"
  (doseq [body [nil sentinel
               {:error {:type sentinel :code sentinel :param sentinel
                        :message sentinel}}
               {:error {:type (str "invalid_request_error-" sentinel)
                        :code (str "invalid_function_parameters-" sentinel)
                        :param (str "tools-" sentinel)}}
               {:error {:type (string/join (repeat 20000 "x"))
                        :code [sentinel] :param {:secret sentinel}}}
               {:error (reduce (fn [v _] {:error v}) sentinel (range 10000))}]]
    (let [[error logs]
          (capture
            #(with-redefs [http/post (fn [& _] {:status 400 :body body})]
               (failure (fn [] (ai/chat provider sentinel prompt {})))))
          record (:ai/diagnostic (ex-data error))]
      (fact (:error-class record) => :unrecognized)
      (fact (:error-code record) => :unrecognized)
      (fact (:parameter record) => :unrecognized)
      (fact (count logs) => 1)
      (fact (string/includes? (pr-str [record logs]) sentinel) => false))))

(facts "historical Grok and Cloudflare validation text yields categories only"
  (doseq [[body expected-class expected-code]
          [[{:code "invalid-argument"
             :error (str "Failed to start sampling: "
                         "[invalid_client_tool_schema] yetibot_clef: "
                         "tool parameter root must be an object type "
                         sentinel)}
            :invalid-request :invalid-tool-schema]
           [{:errors [{:code 1000
                       :message (str "Unsupported parameter: " sentinel)}]}
            :unrecognized :unsupported-parameter]
           [{"errors" [{"code" sentinel
                        "message" (str "No tool output found for function call "
                                       sentinel)}]}
            :unrecognized :missing-tool-output]]]
    (let [[error logs]
          (capture
            #(with-redefs [http/post (fn [& _] {:status 400 :body body})]
               (failure (fn [] (ai/chat provider sentinel prompt {})))))
          record (:ai/diagnostic (ex-data error))]
      (fact (select-keys record [:error-class :error-code :parameter])
            => {:error-class expected-class :error-code expected-code
                :parameter :unrecognized})
      (fact (count logs) => 1)
      (fact (first logs) => ["ai-provider-diagnostic" record])
      (fact (some #(re-find #"SECRET|123456|account|balance|yetibot_clef|1000"
                            (pr-str %)) [(ex-data error) logs]) => nil)
      (fact (.getCause error) => nil)
      (fact (.getMessage error)
            => (str "AI provider " sentinel " returned HTTP 400.")))))

(facts "only known bounded validation phrases are classified"
  (doseq [[message category]
          [["tool parameter root must be an object type" :invalid-tool-schema]
           ["Missing tool output" :missing-tool-output]
           ["Unrecognized parameter: private_field" :unsupported-parameter]
           ["This model's maximum context length is 8192 tokens" :context-limit]
           ["Context length exceeded" :context-limit]
           ["Model not found" :model-access]
           ["The model does not exist or you do not have access to it"
            :model-access]
           [sentinel :unrecognized]
           ["arbitrary tool schema model parameter context" :unrecognized]]]
    (fact (:error-code (diagnostics/error-fields
                        {:error {:message (str message " " sentinel)}}))
          => category))
  (fact (:error-code (diagnostics/error-fields
                      {:error {:code "model_not_found"
                               :message "Missing tool output"}}))
        => :model-access)
  (fact (:error-code (diagnostics/error-fields
                      {:error {:code "invalid_client_tool_schema"}}))
        => :invalid-tool-schema))

(facts
  "text inspection skips oversized and nested values and stops at three errors"
  (let [marker "[invalid_client_tool_schema]"
        bounded (str marker (string/join (repeat (- 1024 (count marker)) "x")))
        deep (reduce (fn [value _] {:message value}) marker (range 10000))]
    (fact (:error-code (diagnostics/error-fields {:error {:message bounded}}))
          => :invalid-tool-schema)
    (doseq [body [{:error {:message (str bounded "x")}}
                 {:error {:message (str (string/join (repeat 20000 "x"))
                                        marker)}}
                 {:error {:message deep}}
                 {:errors [{:message sentinel} {} {}
                           {:message marker}]}
                 {:errors {:message marker}}
                 {:errors (repeat {:message marker})}]]
      (fact (:error-code (diagnostics/error-fields body)) => :unrecognized))
    (fact (:error-code (diagnostics/error-fields
                        {:errors [{} {} {:message marker}]}))
          => :invalid-tool-schema)))

(facts "opaque state means a nonempty encrypted string, not key presence"
  (doseq [item [{:type "reasoning"}
               {:type "reasoning" :encrypted_content nil}
               {:type "reasoning" :encrypted_content ""}
               {:type "reasoning" :encrypted_content {:secret sentinel}}]]
    (fact (:opaque-state? (diagnostics/context
                           provider "/responses" {:input [item]} :follow-up))
          => false))
  (fact (:opaque-state? (diagnostics/context
                         provider "/responses"
                         {:input [{:type "reasoning"
                                   :encrypted_content sentinel}]} :follow-up))
        => true))

(fact "host stage and bounded shape metadata never enter HTTP options"
  (let [wire (atom nil)
        messages [{:type "reasoning" :encrypted_content sentinel}
                  {:type "function_call_output" :output sentinel}
                  {:type sentinel :content sentinel}]]
    (with-redefs [http/post (fn [_ opts]
                             (reset! wire opts)
                             {:status 400 :body {}})]
      (let [error (failure
                    #(ai/chat (assoc provider :api "responses"
                                     :tool-choice "omit") sentinel messages
                              {::diagnostics/stage :delegation
                               :tool_choice "none" :tools []}))
            record (:ai/diagnostic (ex-data error))]
        (select-keys record [:stage :tool-choice :item-count :item-types
                             :opaque-state?])
        => {:stage :delegation :tool-choice :omitted :item-count 3
            :item-types [:reasoning :function-call-output :unrecognized]
            :opaque-state? true}
        (contains? @wire ::diagnostics/stage) => false
        (get-in @wire [:form-params ::diagnostics/stage]) => nil
        (:as @wire) => :json
        (:form-params @wire)
        => {:model sentinel :input messages :store false
            :include ["reasoning.encrypted_content"] :max_turns 6 :tools []}))))

(fact "counts saturate and attacker-controlled selectors are coarse"
  (let [record (diagnostics/context
                 provider "/responses"
                 {:model sentinel :input (repeat 1000 {:type sentinel})
                  :tools (repeat 1000 {}) :tool_choice sentinel}
                 sentinel)]
    (:stage record) => :unrecognized
    (:provider record) => :other
    (:model record) => :other
    (:item-count record) => 256
    (:tool-count record) => 64
    (:tool-choice record) => :unrecognized
    (:item-types record) => [:unrecognized]))

(facts "public codes and parameter families are statically classified"
  (doseq [[code category]
          [["unsupported_parameter" :unsupported-parameter]
           ["missing_tool_output" :missing-tool-output]
           ["context_length_exceeded" :context-limit]
           ["model_not_found" :model-access]
           ["rate_limit_exceeded" :rate-limit]]]
    (fact (:error-code (diagnostics/error-fields {:error {:code code}}))
          => category))
  (doseq [[param category]
          [["input[2]" :input] ["messages.0.content" :messages]
           ["model" :model] ["max_output_tokens" :token-limit]
           ["tool_choice" :tool-choice] ["temperature" :other]
           [sentinel :unrecognized]]]
    (fact (:parameter (diagnostics/error-fields {:error {:param param}}))
          => category)))

(facts "stream HTTP errors inspect bounded JSON then abort before close"
  (doseq [[wire expected]
          [[(json/generate-string
              {:error {:code "invalid_function_parameters" :message sentinel}})
            :invalid-tool-schema]
           [(json/generate-string
              {:code "invalid-argument"
               :error (str "[invalid_client_tool_schema] yetibot_clef: "
                           sentinel)})
            :invalid-tool-schema]
           [(json/generate-string
              {:errors [{:code 1000
                         :message (str "Context length exceeded " sentinel)}]})
            :context-limit]
           ["{malformed" :unrecognized]
           [(str (string/join (repeat 10000 "[")) sentinel) :unrecognized]
           [(string/join (repeat 20000 "x")) :unrecognized]]]
    (let [owner (HttpGet. "https://example.test/v1/responses")
          closed (atom false) reads (atom 0)
          body (proxy [ByteArrayInputStream] [(.getBytes ^String wire "UTF-8")]
                 (read [buffer offset length]
                   (let [n (proxy-super read buffer offset length)]
                     (swap! reads + (max 0 n))
                     n))
                 (close [] (reset! closed (.isAborted owner))))
          [error logs]
          (capture
            #(with-redefs
               [http/post (fn [& _]
                            {:status 400 :body body
                             :request {:http-req owner}})]
               (failure
                 (fn []
                   (ai/chat (assoc provider :api "responses") sentinel prompt
                            {:on-progress identity})))))]
      (fact @closed => true)
      (fact (<= @reads 16385) => true)
      (fact (get-in (ex-data error) [:ai/diagnostic :error-code]) => expected)
      (fact (count logs) => 1)
      (fact (some #(re-find #"SECRET|123456|account|balance|yetibot_clef|1000"
                            (pr-str %)) [(ex-data error) logs]) => nil))))

(facts "error-body read failure preserves HTTP error and cleanup"
  (doseq [exception [(IOException. sentinel)
                     (SocketTimeoutException. sentinel)]]
    (let [owner (HttpGet. "https://example.test/v1/responses")
          closed (atom false)
          body (proxy [ByteArrayInputStream] [(byte-array 0)]
                 (read [buffer offset length] (throw exception))
                 (close [] (reset! closed (.isAborted owner))))]
      (with-redefs [http/post (fn [& _]
                               {:status 400 :body body
                                :request {:http-req owner}})]
        (let [[error logs]
              (capture
                #(failure
                   (fn []
                     (ai/chat (assoc provider :api "responses")
                              sentinel prompt {:on-progress identity}))))]
          (fact @closed => true)
          (fact (.getMessage error)
                => (str "AI provider " sentinel " returned HTTP 400."))
          (fact (get-in (ex-data error) [:ai/diagnostic :error-code])
                => :unrecognized)
          (fact (count logs) => 1))))))

(fact "blocking error reads stop on owner abort, before body close"
  (let [owner (HttpGet. "https://example.test/v1/responses")
        released (CountDownLatch. 1)
        reads (atom 0)
        closed (atom nil)
        body (proxy [InputStream] []
               (read
                 ([] -1)
                 ([buffer offset length]
                  (swap! reads inc)
                  (.await released 5 TimeUnit/SECONDS)
                  (throw (IOException. "synthetic blocked read"))))
               (close [] (reset! closed (.isAborted owner))))]
    (.setCancellable owner
                     (reify Cancellable
                       (cancel [_] (.countDown released) true)))
    (with-redefs [http/post (fn [& _]
                             {:status 400 :body body
                              :request {:http-req owner}})]
      (let [start (System/nanoTime)
            [error logs]
            (capture
              #(failure
                 (fn []
                   (ai/chat (assoc provider :api "responses") sentinel prompt
                            {:on-progress identity}))))
            elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)]
        (fact elapsed-ms => (fn [ms] (< ms 2500)))
        (fact @reads => 1)
        (fact @closed => true)
        (fact (.getCount released) => 0)
        (fact (.size (.getQueue
                       ^ScheduledThreadPoolExecutor
                       @#'diagnostics/abort-executor)) => 0)
        (fact (.getMessage error)
              => (str "AI provider " sentinel " returned HTTP 400."))
        (fact (select-keys (:ai/diagnostic (ex-data error))
                           [:failure :status :error-code])
              => {:failure :http :status 400 :error-code :unrecognized})
        (fact (count logs) => 1)))))

(fact "ownerless potentially blocking bodies are closed but never read"
  (let [reads (atom 0)
        closed (atom false)
        body (proxy [InputStream] []
               (read
                 ([] -1)
                 ([buffer offset length]
                  (swap! reads inc)
                  (.await (CountDownLatch. 1) 5 TimeUnit/SECONDS)
                  -1))
               (close [] (reset! closed true)))]
    (with-redefs [http/post (fn [& _] {:status 400 :body body})]
      (let [start (System/nanoTime)
            [error _]
            (capture
              #(failure
                 (fn []
                   (ai/chat (assoc provider :api "responses") sentinel prompt
                            {:on-progress identity}))))]
        (fact (/ (- (System/nanoTime) start) 1000000.0)
              => (fn [ms] (< ms 2500)))
        (fact @reads => 0)
        (fact @closed => true)
        (fact (get-in (ex-data error) [:ai/diagnostic :error-code])
              => :unrecognized)))))

(fact "ownerless in-memory errors retain safe classification"
  (let [body (ByteArrayInputStream.
               (.getBytes "{\"error\":{\"code\":\"missing_tool_output\"}}"
                          "UTF-8"))]
    (with-redefs [http/post (fn [& _] {:status 400 :body body})]
      (let [[error _]
            (capture
              #(failure
                 (fn []
                   (ai/chat (assoc provider :api "responses") sentinel prompt
                            {:on-progress identity}))))]
        (get-in (ex-data error) [:ai/diagnostic :error-code])
        => :missing-tool-output))))

(facts "diagnostic guards cancel and leave no queued tasks after quick errors"
  (let [scheduled (atom [])
        schedule @#'diagnostics/schedule-abort
        executor @#'diagnostics/abort-executor]
    (with-redefs-fn
      {#'diagnostics/schedule-abort
       (fn [owner]
         (let [guard (schedule owner)]
           (swap! scheduled conj guard)
           guard))}
      (fn []
        (doseq [broken? [false true]]
          (let [owner (HttpGet. "https://example.test/v1/responses")
                wire "{\"error\":{\"code\":\"missing_tool_output\"}}"
                body (proxy [ByteArrayInputStream]
                       [(.getBytes wire "UTF-8")]
                       (read [buffer offset length]
                         (if broken? (throw (IOException. "synthetic"))
                           (proxy-super read buffer offset length))))]
            (fact (:error-code
                    (diagnostics/stream-error-fields body owner))
                  => (if broken? :unrecognized :missing-tool-output))
            ;; Diagnostic inspection never aborts a quick owner itself.
            (fact (.isAborted owner) => false)
            (fact (.isCancelled (last @scheduled)) => true)
            (fact (.size (.getQueue
                           ^ScheduledThreadPoolExecutor executor)) => 0)))
        (fact (count @scheduled) => 2)
        (fact (.getRemoveOnCancelPolicy
                ^ScheduledThreadPoolExecutor executor) => true)))))

(fact "the single daemon scheduler does not inherit ambient thread locals"
  (let [executor ^ScheduledThreadPoolExecutor @#'diagnostics/abort-executor
        ambient (InheritableThreadLocal.)
        seen (promise)]
    (.set ambient sentinel)
    (try
      (let [thread (.newThread
                     (.getThreadFactory executor)
                     (reify Runnable
                       (run [_] (deliver seen (.get ambient)))))]
        (.start thread)
        (.join thread 1000)
        (fact (.isDaemon thread) => true)
        (fact (.getCorePoolSize executor) => 1)
        (fact (deref seen 1000 :not-run) => nil))
      (finally (.remove ambient)))))

(fact "success paths and ownerless memory errors never schedule abort guards"
  (let [scheduled (atom 0)
        answer {:status "completed" :output []}
        wire (str "data: "
                  (json/generate-string
                    {:type "response.completed" :response answer}) "\n\n")]
    (with-redefs-fn
      {#'diagnostics/schedule-abort (fn [_] (swap! scheduled inc))}
      (fn []
        (with-redefs [http/post (fn [& _] {:status 200 :body answer})]
          (fact (ai/chat (assoc provider :api "responses") sentinel prompt {})
                => answer))
        (let [owner (HttpGet. "https://example.test/v1/responses")]
          (with-redefs [http/post
                        (fn [& _]
                          {:status 200
                           :body (ByteArrayInputStream.
                                   (.getBytes wire "UTF-8"))
                           :request {:http-req owner}})]
            (fact (ai/chat (assoc provider :api "responses") sentinel prompt
                           {:on-progress identity}) => answer)
            (fact (.isAborted owner) => true)))
        (fact (:error-code
                (diagnostics/stream-error-fields
                  (ByteArrayInputStream.
                    (.getBytes "{\"error\":{\"code\":\"model_not_found\"}}"
                               "UTF-8"))))
              => :model-access)
        (fact @scheduled => 0)))))

(fact "transport exceptions never carry request objects or stacks forward"
  (let [[error logs]
        (capture
          #(with-redefs
             [http/post (fn [& _]
                          (throw (ex-info sentinel
                                          {:request {:body sentinel}
                                           :response {:body sentinel}
                                           :ai/diagnostic {:raw sentinel}
                                           :ai/error-fields
                                           {:raw sentinel
                                            :error-class sentinel
                                            :error-code {:raw sentinel}
                                            :parameter sentinel}})))]
             (failure (fn [] (ai/chat provider sentinel prompt {})))))]
    (set (keys (ex-data error))) => #{:ai/error :ai/diagnostic}
    (get-in (ex-data error) [:ai/diagnostic :failure]) => :transport
    (get-in (ex-data error) [:ai/diagnostic :status]) => :unrecognized
    (.getCause error) => nil
    (select-keys (:ai/diagnostic (ex-data error))
                 [:error-class :error-code :parameter])
    => {:error-class :unrecognized :error-code :unrecognized
        :parameter :unrecognized}
    (count logs) => 1
    (some #(re-find #"SECRET|123456|account|balance" (pr-str %))
          [(ex-data error) logs]) => nil))

(fact "string Responses input remains supported without capturing text"
  (with-redefs [http/post (fn [& _] {:status 400 :body {}})]
    (let [[error _]
          (capture #(failure
                      (fn []
                        (ai/chat (assoc provider :api "responses") sentinel
                                 sentinel {}))))
          record (:ai/diagnostic (ex-data error))]
      (:item-count record) => 1
      (:item-types record) => [:message]
      (:opaque-state? record) => false)))

(facts "malformed and attacker-controlled SSE errors remain closed"
  (doseq [wire ["data: {malformed\n\n"
                (str "data: "
                     (json/generate-string
                       {:type "error" :code sentinel :param sentinel
                        :message sentinel :model sentinel}) "\n\n")]]
    (let [owner (HttpGet. "https://example.test/v1/responses")
          closed (atom false)
          body (proxy [ByteArrayInputStream] [(.getBytes ^String wire "UTF-8")]
                 (close [] (reset! closed (.isAborted owner))))
          [error logs]
          (capture
            #(with-redefs
               [http/post (fn [& _]
                            {:status 200 :body body
                             :request {:http-req owner}})]
               (failure
                 (fn []
                   (ai/chat (assoc provider :api "responses") sentinel prompt
                            {:on-progress identity})))))
          record (:ai/diagnostic (ex-data error))]
      (fact @closed => true)
      (fact (:error-code record) => :unrecognized)
      (fact (:parameter record) => :unrecognized)
      (fact (:failure record) => :stream)
      (fact (count logs) => 1)
      (fact (some #(re-find #"SECRET|123456|account|balance" (pr-str %))
                  [(ex-data error) logs]) => nil))))

(fact "SSE failed events retain safe categories and generic human error"
  (let [owner (HttpGet. "https://example.test/v1/responses")
        closed (atom false)
        event {:type "response.failed"
               :response {:error {:code "unsupported_parameter"
                                  :param "tool_choice" :message sentinel}}}
        wire (str "data: " (json/generate-string event) "\n\n")
        body (proxy [ByteArrayInputStream] [(.getBytes wire "UTF-8")]
               (close [] (reset! closed (.isAborted owner))))
        [error logs]
        (capture
          #(with-redefs
             [http/post
              (fn [& _]
                {:status 200 :body body :request {:http-req owner}})]
             (failure
               (fn [] (ai/chat (assoc provider :api "responses") sentinel prompt
                               {:on-progress identity})))))]
    @closed => true
    (.getMessage error)
    => "The AI provider returned an invalid or incomplete event stream."
    (select-keys (:ai/diagnostic (ex-data error))
                 [:failure :status :error-code :parameter])
    => {:failure :stream :status 200 :error-code :unsupported-parameter
        :parameter :tool-choice}
    (count logs) => 1
    (string/includes? (pr-str [(ex-data error) logs]) sentinel) => false))
