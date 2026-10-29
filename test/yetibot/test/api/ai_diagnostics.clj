(ns yetibot.test.api.ai-diagnostics
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [taoensso.timbre :as log]
            [yetibot.api.ai :as ai]
            [yetibot.api.ai-diagnostics :as diagnostics])
  (:import [java.io ByteArrayInputStream IOException]
           [java.net SocketTimeoutException]
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
               {:error {:type (apply str (repeat 20000 "x"))
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
      (fact (string/includes? (pr-str [(ex-data error) logs]) sentinel)
            => false))))

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
