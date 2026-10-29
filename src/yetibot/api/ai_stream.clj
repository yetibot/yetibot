(ns yetibot.api.ai-stream
  "Bounded Responses SSE. Only completed envelopes reach tool dispatch."
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [yetibot.api.ai-diagnostics :as diagnostics]
            [yetibot.api.mcp :as mcp])
  (:import [java.io BufferedReader InputStream InputStreamReader]
           [java.nio.charset StandardCharsets CodingErrorAction]))

(defn- fail!
  ([] (fail! nil))
  ([body]
   (throw (ex-info
            "The AI provider returned an invalid or incomplete event stream."
            {:ai/error true
             :ai/error-fields (diagnostics/error-fields body)}))))

(defn clip [^String text limit]
  (let [end (min (.length text) (max 0 limit))
        end (if (and (pos? end)
                     (Character/isHighSurrogate (.charAt text (dec end))))
              (dec end) end)]
    (subs text 0 end)))

(defn tail [^String text limit]
  (let [start (max 0 (- (.length text) limit))
        start (if (and (< start (.length text))
                       (Character/isLowSurrogate (.charAt text start)))
                (inc start) start)]
    (subs text start)))

(defn notify!
  "Deliver bounded public snapshots, never arguments, bodies or opaque state.
   Buffer credential-sized edges so split or cropped tokens cannot leak."
  [callback config event]
  (when callback
    (try
      (let [credentials (mcp/credentials config)
            hold (reduce max 0 (map #(dec (count %)) credentials))
            preview
            (fn [text limit cropped?]
              ;; Same-length masks preserve raw offsets even for short secrets
              ;; whose [redacted] replacements would expand a partial token.
              (let [masked (reduce
                             #(string/replace
                                %1 %2 (String. (char-array (count %2) \u0000)))
                             text credentials)
                    end (max 0 (- (count masked) hold))
                    masked (clip masked end)
                    masked (if cropped?
                             (tail masked (max 0 (- (count masked) hold)))
                             masked)]
                (-> masked (tail limit)
                    (string/replace #"\u0000+" "[redacted]")
                    (tail limit))))
            event (cond-> (select-keys event [:phase :summary :text])
                    (:phase event)
                    (update :phase #(clip (mcp/redact % config) 160))
                    (:summary event)
                    (assoc :summary (preview (:summary event) 500
                                             (:summary-cropped? event)))
                    (:text event)
                    (assoc :text (preview (:text event) 1200
                                          (:text-cropped? event))))]
        (callback event))
      ;; Chat progress is best-effort and must not break inference/tool cleanup.
      (catch Exception _ nil))))

(defn- reader [^InputStream stream]
  (let [used (atom 0)
        deadline (+ (System/nanoTime) (* 120000 1000000))
        check! (fn [n]
                 (when (or (> (swap! used + (max 0 n)) 16777216)
                           (> (System/nanoTime) deadline)
                           (.isInterrupted (Thread/currentThread)))
                   (fail!)))
        bounded (proxy [InputStream] []
                  (read
                    ([] (check! 0)
                        (let [n (.read stream)]
                          (check! (if (neg? n) 0 1)) n))
                    ([buffer offset length]
                     (check! 0)
                     (let [length (min length (inc (- 16777216 @used)))
                           n (.read stream buffer offset length)]
                       (check! n) n))))
        decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (let [input (BufferedReader. (InputStreamReader. bounded decoder))]
      (.mark input 1)
      (when-not (= 65279 (.read input)) (.reset input))
      input)))

(defn- parsed [text]
  (try
    (let [value (json/parse-string text true)]
      (when-not (map? value) (fail!))
      value)
    (catch StackOverflowError _ (fail!))
    (catch Exception _ (fail!))))

(defn- completed [body]
  (when-not (and (map? body) (= "completed" (:status body))
                 (sequential? (:output body)))
    (fail!))
  body)

(defn- search-phase [event native-tools]
  (let [[_ native status]
        (re-matches #"response\.(web_search|x_search)_call\.(.+)"
                    (:type event ""))
        item (:item event)
        native (or native ({"web_search_call" "web_search"
                            "x_search_call" "x_search"} (:type item)))
        label ({"web_search" "web" "x_search" "X"} native)]
    (when (and label (contains? native-tools native))
      (if (or (= "completed" status)
              (= "response.output_item.done" (:type event)))
        (str "Finished " label " search")
        (str "Searching " label)))))

(defn read-response
  "Read SSE (or JSON from a non-streaming proxy) without replaying requests.
   A frame is at most 4 MiB characters; the wire budget is 16 MiB / 120 seconds.
   The caller owns abort-before-close cleanup of the underlying HTTP response."
  [stream content-type native-tools callback config]
  (let [input (reader stream)
        state (atom {:phase "Thinking" :summary "" :text ""})
        publish! (fn [event]
                   (notify! callback config (swap! state merge event)))]
    (publish! @state)
    (if (string/includes? (string/lower-case (or content-type ""))
                          "application/json")
      (completed (parsed (slurp input)))
      (loop [frame (StringBuilder.)]
        (let [line (.readLine input)]
          (when (or (nil? line) (> (count line) 4194304)) (fail!))
          (cond
            (and (empty? line) (pos? (.length frame)))
            (let [event (parsed (.toString frame))
                  kind (:type event)
                  phase (when (string? kind)
                          (search-phase event native-tools))]
              (when-not (string? kind) (fail!))
              (cond
                (contains? #{"response.completed" "response.done"} kind)
                (completed (:response event))
                (contains? #{"error" "response.failed" "response.incomplete"}
                           kind) (fail! (or (:response event) event))
                :else
                (do
                  (cond
                    (contains? #{"response.output_text.delta"
                                 "response.reasoning_summary_text.delta"
                                 "response.reasoning_text.delta"} kind)
                    (let [key (if (= "response.output_text.delta" kind)
                                :text :summary)]
                      (when-not (string? (:delta event)) (fail!))
                      (let [text (str (get @state key) (:delta event))
                            cropped (if (= key :text)
                                      :text-cropped? :summary-cropped?)]
                        (publish!
                          {:phase (if (= key :text) "Answering" "Thinking")
                           key (tail text 8192)
                           cropped (or (get @state cropped)
                                       (> (count text) 8192))})))
                    phase (publish! {:phase phase})
                    (and (= "response.output_item.added" kind)
                         (= "function_call" (get-in event [:item :type])))
                    (publish! {:phase "Preparing a tool request"}))
                  (recur (StringBuilder.)))))
            (string/starts-with? line "data:")
            (let [data (subs line (if (string/starts-with? line "data: ") 6 5))]
              (.append frame data)
              (.append frame \newline)
              (when (> (.length frame) 4194304) (fail!))
              (recur frame))
            :else (recur frame)))))))
