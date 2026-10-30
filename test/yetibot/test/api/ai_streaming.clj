(ns yetibot.test.api.ai-streaming
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai :as ai])
  (:import [java.io ByteArrayInputStream SequenceInputStream]
           [java.util Collections]
           [org.apache.http.client.methods HttpGet]))

(def provider
  {:name "grok" :url "https://api.x.ai/v1" :key "provider-secret"
   :api "responses" :native-tools ["web_search" "x_search"]})

(def answer
  {:status "completed"
   :output [{:type "reasoning" :encrypted_content "OPAQUE-DO-NOT-DISPLAY"}
            {:type "message" :role "assistant"
             :content [{:type "output_text" :text "Final answer. 😀"}]}]
   :usage {:input_tokens 12 :output_tokens 4}})

(defn bytes-stream [text]
  (ByteArrayInputStream. (.getBytes ^String text "UTF-8")))

(defn events-text [events]
  (apply str (map #(str "data: " (json/generate-string %) "\n\n") events)))

(defn error-message [f]
  (try (f) nil (catch Exception e (.getMessage e))))

(fact "Responses streaming returns the authoritative completed envelope"
  (let [events (atom []) request (atom nil)
        wire [{:type "response.web_search_call.searching"}
              {:type "response.x_search_call.in_progress"}
              {:type "response.reasoning_summary_text.delta"
               :delta "Checking several sources before answering the question."}
              {:type "response.reasoning_text.delta"
               :delta " More provider-exposed thinking."}
              {:type "response.function_call_arguments.delta"
               :delta "PRIVATE-ARGUMENTS"}
              {:type "response.output_item.added"
               :item {:type "reasoning" :encrypted_content "PRIVATE-CIPHER"}}
              {:type "response.output_text.delta"
               :delta "Here is a draft answer long enough to preview."}
              {:type "response.completed" :response answer}]]
    (with-redefs [client/post
                  (fn [_ options]
                    (reset! request options)
                    {:status 200 :headers {"content-type" "text/event-stream"}
                     :body (bytes-stream (events-text wire))})]
      (ai/chat provider "grok-4.7" []
               {:on-progress #(swap! events conj %)}) => answer
      (:as @request) => :stream
      (:save-request @request) => true
      (get-in @request [:form-params :stream]) => true
      (get-in @request [:form-params :store]) => false
      (get-in @request [:form-params :include])
      => ["reasoning.encrypted_content"]
      (contains? (:form-params @request) :on-progress) => false
      (some #(= "Searching web" (:phase %)) @events) => true
      (some #(= "Searching X" (:phase %)) @events) => true
      (boolean (some #(string/includes? (:summary % "") "Checking") @events))
      => true
      (boolean (some #(string/includes? (:text % "") "draft answer") @events))
      => true
      (some #(re-find #"PRIVATE|OPAQUE" (pr-str %)) @events) => nil)))

(fact "completed answer items survive continued work without duplicate delivery"
  (let [seen (atom []) posts (atom 0)
        first-answer {:id "message-1" :type "message" :role "assistant"
                      :status "completed"
                      :content [{:type "output_text" :text "First provider-"}
                                {:type "output_text"
                                 :text "secret and other-secret."}]}
        next-answer {:id "message-2" :type "message" :role "assistant"
                     :status "completed"
                     :content [{:type "output_text" :text "Next answer."}]}
        body {:status "completed" :output [first-answer next-answer]}
        wire (events-text
               [{:type "response.output_item.done" :output_index 0
                 :item first-answer}
                {:type "response.output_item.done" :output_index 0
                 :item first-answer}
                {:type "response.reasoning_text.delta"
                 :delta "Still working with a safe trailing buffer."}
                {:type "response.output_item.done"
                 :item {:type "function_call" :arguments "PRIVATE"}}
                {:type "response.completed" :response body}])]
    (with-redefs [client/post
                  (fn [& _]
                    (swap! posts inc)
                    {:status 200 :body (bytes-stream wire)})]
      (ai/chat provider "grok-4.7" []
               {:on-progress #(swap! seen conj %)
                :progress-config {:secrets ["other-secret"]}}) => body
      (vec (keep :answer @seen))
      => ["First [redacted] and [redacted]." "Next answer."]
      @posts => 1
      (some #(re-find #"provider-|other-secret|PRIVATE" (pr-str %)) @seen)
      => nil)))

(fact "completed answers are bounded after credential replacement"
  (let [seen (atom [])
        item {:type "message" :role "assistant"
              :content [{:type "output_text"
                         :text (str (String. (char-array 65528 (char 97)))
                                    "S")}]}
        body {:status "completed" :output [item]}]
    (with-redefs [client/post
                  (fn [& _]
                    {:status 200 :headers {"content-type" "application/json"}
                     :body (bytes-stream (json/generate-string body))})]
      (ai/chat provider "grok-4.7" []
               {:on-progress #(swap! seen conj %)
                :progress-config {:secrets ["S"]}}) => body
      (some :answer-limit? @seen) => true
      (keep :answer @seen) => [])))

(facts "SSE errors or EOF cannot become a completed response"
  (doseq [wire [[{:type "response.output_text.delta" :delta "Partial"}]
                [{:type "response.incomplete"
                  :response {:status "incomplete" :error "PRIVATE"}}]
                [{:type "response.failed" :error {:message "PRIVATE"}}]
                [{:type "error" :message "PRIVATE"}]
                [{:type "response.completed"
                  :response {:status "incomplete" :output []}}]]]
    (with-redefs [client/post
                  (fn [& _]
                    {:status 200 :headers {"content-type" "text/event-stream"}
                     :body (bytes-stream (events-text wire))})]
      (fact
        (error-message #(ai/chat provider "grok-4.7" []
                                 {:on-progress identity}))
        => "The AI provider returned an invalid or incomplete event stream."))))

(fact "SSE handles comments, CRLF and multiline data without losing Unicode"
  (let [wire (str "\uFEFF: heartbeat\r\nevent: response.completed\r\n"
                  (->> (string/split-lines
                       (json/generate-string
                         {:type "response.completed" :response answer}
                         {:pretty true}))
                       (map #(str "data: " % "\r\n")) (apply str))
                  "\r\n")]
    (with-redefs [client/post
                  (fn [& _]
                    {:status 200 :body (bytes-stream wire)})]
      (ai/chat provider "grok-4.7" [] {:on-progress identity}) => answer)))

(fact "a provider that ignores streaming may return a bounded JSON envelope"
  (with-redefs [client/post
                (fn [& _]
                  {:status 200 :headers {"content-type" "application/json"}
                   :body (bytes-stream (json/generate-string answer))})]
    (ai/chat provider "grok-4.7" [] {:on-progress identity}) => answer))

(fact "credential prefixes split across events never enter previews"
  (let [seen (atom [])
        wire (events-text
               [{:type "response.output_text.delta"
                 :delta "Some harmless introduction. provider-"}
                {:type "response.output_text.delta"
                 :delta "secret followed by other-secret and more safe text."}
                {:type "response.completed" :response answer}])]
    (with-redefs [client/post
                  (fn [& _] {:status 200 :body (bytes-stream wire)})]
      (ai/chat provider "grok-4.7" []
               {:on-progress #(swap! seen conj %)
                :progress-config
                {:servers [{:headers {"Authorization"
                                      "Bearer other-secret"}}]}}) => answer
      (some #(re-find #"provider-|other-secret" (pr-str %)) @seen) => nil
      (boolean (some #(string/includes? (:text % "") "[redacted]") @seen))
      => true)))

(fact "short redaction values cannot expose a longer credential fragment"
  (let [seen (atom [])
        wire (events-text
               [{:type "response.output_text.delta"
                 :delta "Some harmless introduction. provider-"}
                {:type "response.output_text.delta"
                 :delta "secret with enough harmless text after it."}
                {:type "response.completed" :response answer}])]
    (with-redefs [client/post
                  (fn [& _] {:status 200 :body (bytes-stream wire)})]
      (ai/chat provider "grok-4.7" []
               {:on-progress #(swap! seen conj %)
                :progress-config {:secrets ["r"]}}) => answer
      (some #(string/includes? (:text % "") "p[redacted]ov") @seen)
      => nil)))

(fact "long previews keep moving rather than freezing on the first excerpt"
  (let [seen (atom [])
        wire (events-text
               [{:type "response.reasoning_summary_text.delta"
                 :delta (String. (char-array 9000 \a))}
                {:type "response.reasoning_summary_text.delta"
                 :delta "LATEST_THINKING with a safe trailing buffer."}
                {:type "response.output_text.delta"
                 :delta (String. (char-array 9000 \a))}
                {:type "response.output_text.delta"
                 :delta "LATEST_ANSWER with a safe trailing buffer."}
                {:type "response.completed" :response answer}])]
    (with-redefs [client/post
                  (fn [& _] {:status 200 :body (bytes-stream wire)})]
      (ai/chat provider "grok-4.7" []
               {:on-progress #(swap! seen conj %)}) => answer
      (string/includes? (:summary (last @seen)) "LATEST_THINKING") => true
      (string/includes? (:text (last @seen)) "LATEST_ANSWER") => true)))

(fact "no-tools also suppresses unsolicited native search progress"
  (let [seen (atom []) request (atom nil)]
    (with-redefs [client/post
                  (fn [_ options]
                    (reset! request options)
                    {:status 200
                     :body (bytes-stream
                             (events-text
                               [{:type "response.web_search_call.searching"}
                                {:type "response.completed"
                                 :response answer}]))})]
      (ai/chat provider "grok-4.7" []
               {:tool_choice "none" :on-progress #(swap! seen conj %)})
      => answer
      (get-in @request [:form-params :tools]) => []
      (some #(= "Searching web" (:phase %)) @seen) => nil)))

(facts "oversized events and total wire bytes fail closed"
  (let [large-event (bytes-stream
                      (str "data: " (String. (char-array 4194305 \x)) "\n\n"))
        chunk (.getBytes (str ":" (String. (char-array 65536 \x)) "\n")
                         "UTF-8")
        large-stream (SequenceInputStream.
                       (Collections/enumeration
                         (conj (vec (repeatedly 257
                                               #(ByteArrayInputStream. chunk)))
                               (bytes-stream
                                 (events-text
                                   [{:type "response.completed"
                                     :response answer}])))))]
    (doseq [body [large-event large-stream]]
      (with-redefs [client/post (fn [& _] {:status 200 :body body})]
        (fact (error-message #(ai/chat provider "grok-4.7" []
                                      {:on-progress identity}))
              => (str "The AI provider returned an invalid or incomplete "
                      "event stream."))))))

(fact "HTTP errors abort before close and never expose the provider body"
  (let [owner (HttpGet. "https://api.x.ai/v1/responses")
        aborted (atom nil)
        body (proxy [ByteArrayInputStream] [(.getBytes "PRIVATE" "UTF-8")]
               (close [] (reset! aborted (.isAborted owner))))]
    (with-redefs [client/post
                  (fn [& _]
                    {:status 401 :body body
                     :request {:http-req owner}})]
      (error-message #(ai/chat provider "grok-4.7" []
                               {:on-progress identity}))
      => "AI provider grok returned HTTP 401."
      @aborted => true)))

(fact "previews truncate Unicode without broken surrogate pairs"
  (let [seen (atom [])
        text (str "a" (apply str (repeat 1000 "😀")))
        wire (events-text [{:type "response.output_text.delta" :delta text}
                           {:type "response.completed" :response answer}])]
    (with-redefs [client/post
                  (fn [& _] {:status 200 :body (bytes-stream wire)})]
      (ai/chat provider "grok-4.7" []
               {:on-progress #(swap! seen conj %)}) => answer
      (let [preview (:text (last @seen))]
        (<= (count preview) 1200) => true
        (= preview (String. (.getBytes ^String preview "UTF-8") "UTF-8"))
        => true))))

(fact "non-Responses providers keep their existing transport"
  (let [request (atom nil)
        message {:choices [{:message {:role "assistant" :content "Hi"}}]}]
    (with-redefs [client/post
                  (fn [_ options] (reset! request options)
                    {:status 200 :body message})]
      (ai/chat (assoc provider :api "chat-completions") "custom" []
               {:on-progress identity}) => message
      (:as @request) => :json
      (contains? (:form-params @request) :stream) => false
      (contains? (:form-params @request) :on-progress) => false)))

(fact "progress delivery failure never discards a completed model response"
  (with-redefs [client/post
                (fn [& _]
                  {:status 200 :body (bytes-stream
                                      (events-text
                                        [{:type "response.output_text.delta"
                                          :delta "Preview text"}
                                         {:type "response.completed"
                                          :response answer}]))})]
    (ai/chat provider "grok-4.7" []
             {:on-progress #(throw (Exception. "PRIVATE"))}) => answer))
