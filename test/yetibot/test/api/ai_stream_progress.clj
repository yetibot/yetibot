(ns yetibot.test.api.ai-stream-progress
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]
            [yetibot.api.ai-stream :as stream])
  (:import [java.io ByteArrayInputStream]))

(defn read-events [wire enabled config seen]
  (stream/read-response
    (ByteArrayInputStream.
      (.getBytes
        (string/join
          (map #(str "data: " (json/generate-string %) "\n\n") wire))
        "UTF-8"))
    "text/event-stream" enabled #(swap! seen conj %) config))

(def item {:id "PRIVATE-ID" :type "web_search_call"
           :action {:query "PRIVATE-QUERY"} :status "completed"})
(def body {:status "completed" :output [item]})

(fact "ai-stream native rows dedup frames and never expose private identity"
  (let [seen (atom [])]
    (read-events
      [{:type "response.output_item.added" :output_index 0
        :item (assoc item :status "in_progress")}
       {:type "response.web_search_call.in_progress" :item_id "PRIVATE-ID"
        :output_index 0}
       {:type "response.web_search_call.searching" :item_id "PRIVATE-ID"}
       {:type "response.output_item.done" :output_index 0 :item item}
       {:type "response.web_search_call.completed" :item_id "PRIVATE-ID"}
       {:type "response.completed" :response body}]
      #{"web_search"} {:progress-response 3} seen) => body
    (vec (keep :tool-event @seen))
    => [{:domain :native :response 3 :call 1 :label "web search"
         :outcome :running}
        {:domain :native :response 3 :call 1 :label "web search"
         :outcome :success}]
    (some #(re-find #"PRIVATE" (pr-str %)) @seen) => nil))

(facts
  "ai-stream terminal-only native envelopes and response identity isolation"
  (let [seen (atom [])]
    (doseq [response [1 2]]
      (read-events [{:type "response.completed" :response body}]
                   #{"web_search"} {:progress-response response} seen))
    (mapv (juxt :response :call :outcome) (keep :tool-event @seen))
    => [[1 1 :success] [2 1 :success]]))

(fact "ai-stream ignores no-tools native frames and function preparation"
  (let [seen (atom [])]
    (read-events
      [{:type "response.web_search_call.searching" :item_id "PRIVATE-ID"}
       {:type "response.output_item.added"
        :item {:id "PRIVATE-ID" :type "function_call"}}
       {:type "response.completed" :response body}] #{} {} seen) => body
    (keep :tool-event @seen) => []))

(fact "ai-stream structured notify allowlist rejects malformed or opaque fields"
  (let [seen (atom [])
        event {:domain :host :call 1 :label "yetibot_help" :outcome :running}]
    (stream/notify! #(swap! seen conj %) {}
                    {:tool-event (assoc event :arguments "PRIVATE")
                     :tool-budget {:used 1 :limit 6 :remaining 5
                                   :config "PRIVATE"}
                     :opaque "PRIVATE" :answer "Immediate answer"})
    (first @seen)
    => {:tool-event event :tool-budget {:used 1 :limit 6 :remaining 5}
        :answer "Immediate answer"}
    (stream/notify! #(swap! seen conj %) {:secrets ["secret"]}
                    {:tool-event (assoc event :label "yetibot_secret")})
    (:label (:tool-event (last @seen))) => "[unavailable-or-redacted-tool]"
    (stream/notify! #(swap! seen conj %) {}
                    {:tool-event (assoc event :outcome :invented)
                     :tool-budget {:used 3 :limit 1 :remaining 0}})
    (last @seen) => {}))

(fact "native same-kind calls remain distinct and terminal failure is truthful"
  (let [seen (atom []) second-item (assoc item :id "PRIVATE-SECOND")]
    (read-events
      [{:type "response.output_item.added" :output_index 0
        :item (assoc item :status "in_progress")}
       {:type "response.output_item.added" :output_index 1
        :item (assoc second-item :status "in_progress")}
       {:type "response.web_search_call.failed" :item_id "PRIVATE-ID"}
       {:type "response.output_item.done" :output_index 1
        :item (dissoc second-item :status)}
       {:type "response.completed"
        :response {:status "completed" :output []}}]
      #{"web_search"} {} seen)
    (mapv (juxt :call :outcome) (keep :tool-event @seen))
    => [[1 :running] [2 :running] [1 :error] [2 :unknown]]))

(fact "native private index-to-ID aliases do not allocate duplicate public rows"
  (let [seen (atom [])]
    (read-events
      [{:type "response.web_search_call.in_progress" :output_index 0}
       {:type "response.output_item.added" :output_index 0
        :item (assoc item :status "in_progress")}
       {:type "response.web_search_call.completed" :item_id "PRIVATE-ID"}
       {:type "response.completed" :response body}]
      #{"web_search"} {} seen)
    (mapv (juxt :call :outcome) (keep :tool-event @seen))
    => [[1 :running] [1 :success]]))

(fact "native running rows become unknown on incomplete stream, not success"
  (let [seen (atom [])]
    (try
      (read-events
        [{:type "response.web_search_call.searching" :item_id "PRIVATE-ID"}
         {:type "response.failed" :message "PRIVATE-FAILURE"}]
        #{"web_search"} {} seen)
      (catch Exception _ nil))
    (mapv :outcome (keep :tool-event @seen)) => [:running :unknown]
    (some #(re-find #"PRIVATE" (pr-str %)) @seen) => nil))
