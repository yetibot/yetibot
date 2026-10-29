(ns yetibot.api.gemma-tools
  "Strict bounded decoding of Gemma 4's native function-call envelope.
   This is data parsing, not a command/prose interpreter. No reader or eval."
  (:require [clojure.string :as string]))

(def error-message "AI provider returned an unsupported tool response.")

(def ^:private number-pattern
  (re-pattern (str "-?(?:0|[1-9][0-9]*)"
                   "(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")))

(defn unsupported! []
  ;; Never attach output, parser internals or the original exception.
  (throw (ex-info error-message {:ai/error true})))

(defn control-output? [text]
  (and (string? text)
       ;; Recognize only native leading control, never extract from prose.
       ;; Unsupported thought/response envelopes also must not leak as answers.
       (some #(string/starts-with? (string/triml text) %)
             ["<|tool_call" "<|tool_response>" "<|channel>"])))

(defn- valid-text? [^String text]
  (loop [i 0]
    (if (= i (.length text)) true
      (let [c (.charAt text i)]
        (cond
          (zero? (int c)) false
          (Character/isHighSurrogate c)
          (and (< (inc i) (.length text))
               (Character/isLowSurrogate (.charAt text (inc i)))
               (recur (+ i 2)))
          (Character/isLowSurrogate c) false
          :else (recur (inc i)))))))

(defn parse-calls
  "Decode only a complete tool-control envelope. Returns string-keyed JSON
   arguments; throws a static error for malformed/oversized input. Strings use
   Gemma's literal <|\"|> delimiters, NOT JSON/EDN string escaping."
  [text]
  (try
    (when-not (and (string? text) (<= (count text) 65536)
                   (<= (alength (.getBytes ^String text "UTF-8")) 65536))
      (unsupported!))
    (let [position (atom 0) nodes (atom 0) size (count text)]
      (letfn [(at? [token] (.startsWith ^String text ^String token @position))
              (take! [token]
                (when-not (at? token) (unsupported!))
                (swap! position + (count token)))
              (space! []
                (while (and (< @position size)
                            (contains? #{\space \tab \newline \return}
                                       (.charAt ^String text @position)))
                  (swap! position inc)))
              (match! [pattern]
                (let [m (re-matcher pattern text)]
                  (.region m @position size)
                  (when-not (.lookingAt m) (unsupported!))
                  (let [value (.group m)]
                    (reset! position (.end m)) value)))
              (identifier! [] (match! #"[A-Za-z_][A-Za-z0-9_]{0,127}"))
              (literal-string! []
                (take! "<|\"|>")
                (let [start @position
                      end (.indexOf ^String text "<|\"|>" start)]
                  (when (or (neg? end) (> (- end start) 16384))
                    (unsupported!))
                  (let [value (subs text start end)]
                    ;; Other protocol tokens are never embedded control/data.
                    (when (or (string/includes? value "<|")
                              (string/includes? value "|>")
                              (not (valid-text? value)))
                      (unsupported!))
                    (reset! position (+ end 5)) value)))
              (number! []
                (let [spelling (match! number-pattern)]
                  (when (> (count spelling) 64) (unsupported!))
                  (let [n (bigdec spelling)]
                    (when (or (> (Math/abs
                                   (long (.scale ^java.math.BigDecimal n)))
                                 308)
                              (> (.abs ^java.math.BigDecimal n)
                                 1000000000000000M)
                              (not (Double/isFinite (double n))))
                      (unsupported!))
                    (if (re-find #"[.eE]" spelling) n (long n)))))
              (object! [depth]
                (take! "{") (space!)
                (if (at? "}") (do (take! "}") {})
                  (loop [result {}]
                    (when (>= (count result) 128) (unsupported!))
                    (let [key (identifier!)]
                      (when (contains? result key) (unsupported!))
                      (space!) (take! ":") (space!)
                      (let [result (assoc result key (value! (inc depth)))]
                        (space!)
                        (if (at? "}") (do (take! "}") result)
                          (do (take! ",") (space!) (recur result))))))))
              (array! [depth]
                (take! "[") (space!)
                (if (at? "]") (do (take! "]") [])
                  (loop [result []]
                    (let [result (conj result (value! (inc depth)))]
                      (space!)
                      (if (at? "]") (do (take! "]") result)
                        (do (take! ",") (space!) (recur result)))))))
              (value! [depth]
                (when (or (> depth 16) (> (swap! nodes inc) 4096))
                  (unsupported!))
                (cond
                  (at? "<|\"|>") (literal-string!)
                  (at? "{") (object! depth)
                  (at? "[") (array! depth)
                  (at? "true") (do (take! "true") true)
                  (at? "false") (do (take! "false") false)
                  (at? "null") (do (take! "null") nil)
                  :else (number!)))]
        (space!)
        (loop [calls []]
          (when (>= (count calls) 20) (unsupported!))
          (take! "<|tool_call>") (take! "call:")
          (let [name (identifier!)]
            (space!)
            (let [arguments (value! 0)]
              (when-not (map? arguments) (unsupported!))
              (space!) (take! "<tool_call|>") (space!)
              (let [calls (conj calls {:name name :arguments arguments})]
                (if (at? "<|tool_call>") (recur calls)
                  (do
                    (when (at? "<|tool_response>")
                      (take! "<|tool_response>") (space!))
                    (when-not (= size @position) (unsupported!))
                    calls))))))))
    (catch Exception _ (unsupported!))))
