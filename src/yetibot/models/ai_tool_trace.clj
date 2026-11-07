(ns ^{:doc (clojure.core/str
             "Request-local, privacy-first tool failure reports. "
             "Never stores raw calls/results.")}
  yetibot.models.ai-tool-trace
  (:refer-clojure :exclude [bytes])
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [yetibot.models.ai-progress :as progress]
            [yetibot.api.mcp :as mcp]))

(def reasons
  {:partial-result "Some tool calls did not return a usable result."
   :unknown-result
   (str "A tool's delivery outcome is uncertain. Later calls were not run; "
        "no further parent request was sent.")
   :malformed-batch
   (str "The AI provider returned a malformed tool-call collection. "
        "No tools from that response were run.")
   :budget-exceeded
   (str "The AI provider exceeded the tool-call limit. "
        "No tools from that response were run.")
   :duplicate-ids
   (str "The AI provider returned duplicate tool-call IDs. "
        "No tools from that response were run.")
   :repeated-id
   (str "The AI provider reused a prior tool-call ID. "
        "No tools from that response were run.")
   :repeat-action
   (str "The AI provider tried to repeat a tool call. "
        "No tools from that response were run.")
   :malformed-call
   (str "The AI provider returned a malformed tool call. "
        "No tools from that response were run.")
   :unavailable-tool
   (str "The AI provider requested an unavailable tool. "
        "No tools from that response were run.")
   :invalid-arguments
   (str "The AI provider sent invalid tool arguments. "
        "No tools from that response were run.")
   :exclusive-batch "Stage changes in a separate, single-tool response."
   :uncertain-result
   (str "The AI provider requested more tools after an uncertain result. "
        "No further tools were run.")
   :tool-exception
   (str "A tool call failed with an unconfirmed outcome. "
        "Later calls were not run.")
   :provider-failure
   (str "The AI provider request failed or returned "
        "an incomplete response.")})

(defn bytes [x]
  (alength (.getBytes ^String (json/generate-string x) "UTF-8")))

(defn redactor [config messages]
  (let [compiled (delay
                   (mcp/metadata-redactor
                     (update config :secrets concat (keep :content messages))))]
    (fn [text] (@compiled text))))

(defn- field [schema k]
  (get schema k (get schema (name k))))

(defn- arguments [raw schema redact]
  (let [value (when (and (string? raw) (<= (count raw) 16384))
                (try (json/parse-string raw) (catch Exception _ nil)))
        nodes (atom 0)
        known-keys #{"action" "operation" "provider" "model" "limit" "page"
                     "offset" "before_id" "after" "count" "max_output_tokens"
                     "query" "prompt" "input" "input_json" "fields" "questions"
                     "type" "items" "unit" "decimals" "instructions" "criteria"
                     "images" "top" "bottom" "template_id" "topic"}
        selectors #{"action" "operation" "type" "items" "unit"}
        pagination #{"limit" "page" "offset" "before_id" "count"
                     "max_output_tokens" "decimals"}
        types #{"text" "number" "date" "time" "datetime" "duration" "list"
                "estimate" "forecast" "noul" "choice" "score"}]
    (letfn [(clean [x s k depth]
              (cond
                (or (> depth 5) (> (swap! nodes inc) 64))
                "[omitted: structure limit]"
                (#{"prompt" "input" "input_json" "query" "instructions"
                   "criteria" "images" "top" "bottom"} k) "[redacted]"
                (map? x)
                (let [entries (take 12 x)
                      result
                      (into (array-map)
                            (map-indexed
                              (fn [i [key v]]
                                (let [spelling (if (keyword? key)
                                                 (subs (str key) 1) key)
                                      safe-key (when (string? spelling)
                                                 (redact spelling))
                                      known? (and (= safe-key spelling)
                                                  (known-keys spelling))]
                                  [(if known? spelling
                                     (str "[redacted-key-" (inc i) "]"))
                                   (clean
                                     v (or (get (field s :properties) key)
                                           (get (field s :properties)
                                                (keyword key)))
                                     (when known? spelling) (inc depth))]))
                              entries))]
                  (cond-> result (> (count x) 12)
                    (assoc "[omitted]" "Additional argument entries omitted.")))
                (vector? x)
                (cond-> (mapv #(clean % (field s :items) nil (inc depth))
                              (take 8 x))
                  (> (count x) 8) (conj "[omitted: additional items]"))
                (string? x)
                (let [safe (redact x)]
                  (if (and (selectors k) (= safe x)
                           (<= (count safe) 64)
                           (re-matches #"[a-zA-Z0-9_-]+" safe)
                           (or (some #{safe} (field s :enum))
                               (and (#{"type" "items"} k) (types safe))))
                    safe "[redacted]"))
                (and (pagination k) (integer? x) (<= 0 x 1000000000))
                (if (= (str x) (redact (str x))) x "[redacted]")
                (nil? x) nil
                :else "[redacted]"))]
      (let [safe (if (map? value) (clean value schema nil 0)
                   {:omitted "Invalid or oversized arguments."})]
        (if (<= (bytes safe) 768) safe
          {:omitted "Redacted arguments exceeded the trace byte limit."})))))

(defn row [call entries redact]
  (let [entry (get entries (get-in call [:function :name]))
        label (:name entry)
        safe-label (when label (redact label))]
    {:tool (if (and safe-label (= label safe-label)
                    (re-matches #"[a-zA-Z0-9_-]{1,64}" safe-label))
             safe-label "[unavailable-or-redacted-tool]")
     :arguments (if entry
                  (arguments (get-in call [:function :arguments])
                             (get-in entry [:tool :inputSchema]) redact)
                  {:omitted "Unavailable tool arguments withheld."})
     :outcome :rejected :attempted false :confirmed false}))

(defn add-batch! [state calls entries redact]
  (let [start (:next-call @state)
        total (count calls)]
    (doseq [[i call] (map-indexed vector (take 40 calls))]
      (let [r (assoc (row call entries redact) :call (+ start i 1))]
        (swap! state
               (fn [s]
                 (if (and (< (count (:calls s)) 40)
                          (<= (bytes (conj (:calls s) r)) 10000))
                   (update s :calls conj r)
                   (update s :omitted inc))))))
    (swap! state #(-> % (update :omitted + (max 0 (- total 40)))
                     (update :next-call + total) (assoc :requested total)))
    start))

(defn outcome! [state ordinal outcome attempted confirmed]
  (swap! state update :calls
         (fn [rows]
           (mapv #(if (= ordinal (:call %))
                    (assoc % :outcome outcome :attempted attempted
                             :confirmed confirmed) %)
                 rows))))

(defn detail! [state ordinal details]
  (swap! state
    (fn [s]
      (let [rows (mapv #(if (= ordinal (:call %)) (merge % details) %)
                       (:calls s))
            rows (if (<= (bytes rows) 11500) rows
                   (mapv #(assoc % :details-shortened true
                                  :arguments {:omitted "Execution details retained."})
                         rows))]
        (loop [rows rows omitted (:omitted s)]
          (if (<= (bytes rows) 11500)
            (assoc s :calls rows :omitted omitted)
            (recur (pop rows) (inc omitted))))))))

(defn- outcome-kind [{:keys [outcome operation code]}]
  (cond
    (= :unknown outcome) :unknown
    (#{:skipped :rejected} outcome) :not-run
    (= :inference operation)
    (case code
      :output-limit :limit
      :no-answer :empty
      :unsupported-output :unsupported
      :incomplete-output :incomplete
      (:request-rejected :zen-access-denied :zen-rate-limit
                         :zen-free-usage-limit) :rejected
      :model-failed)
    :else :tool-failed))

(defn remember-failure!
  "Keep three public failure receipts independently of chronological trace bounds."
  [state row]
  (let [row (select-keys row [:call :tool :outcome :operation :provider :model
                              :code :http_status :error-class :error-code
                              :parameter :output-cap :output_count :thinking_count])
        row (reduce (fn [r k]
                      (if (string? (get r k))
                        (update r k progress/clip 200) r))
                    row [:tool :provider :model])]
    (swap! state
      (fn [s]
        (-> s
            (update :failures
                    #(vec (take 3 (sort-by
                                    (fn [r] (if (= :unknown (:outcome r)) 0 1))
                                    (conj (or % []) row)))))
            (update-in [:failure-counts (outcome-kind row)] (fnil inc 0)))))))

(defn report [state limit reason]
  (let [{:keys [used requested calls omitted failures failure-counts]} @state]
    (loop [rows calls omitted omitted]
      (let [r (cond->
                {:reason reason :budget {:limit limit :used used
                                         :remaining (max 0 (- limit used))
                                         :requested requested}
                 :calls rows :omitted omitted
                 :truncated (boolean
                              (or (pos? omitted) (some :details-shortened rows)))}
                (seq failures) (assoc :failures failures
                                     :failure-counts failure-counts))]
        ;; Leave room for a closed batch-validation code added by the caller.
        (if (<= (bytes r) 11900) r
          (recur (pop rows) (inc omitted)))))))

(defn fail!
  ([reason] (fail! reason nil))
  ([reason code]
   (throw (ex-info (get reasons reason)
                   (cond-> {::reason reason}
                     (contains? progress/failure-labels code)
                     (assoc ::code code))))))

(defn failure-reason
  "Authored explanations only; never exception or provider message text."
  [{:keys [outcome code error-code error-class parameter http_status]} report]
  (cond
    (= :unknown outcome)
    "Delivery could not be confirmed. Not retried; cost may be unknown."
    (= :skipped outcome) "Not run after an earlier uncertain outcome."
    (= :rejected outcome)
    (str "Batch not run: " (or (progress/failure-labels (:code report))
                              (get reasons (:reason report))
                              "validation failed."))
    (or (= :authentication error-class) (= 401 http_status))
    "Provider authentication failed."
    (= :zen-free-usage-limit code)
    "Zen free-usage limit reached. No paid fallback was used."
    (= :unsupported-parameter error-code)
    (if (= :token-limit parameter)
      "Provider does not support the generation-budget parameter used."
      "Provider does not support a request parameter.")
    (= :model-access error-code)
    "Provider denied access or the selected model is unavailable."
    (= :context-limit error-code) "Input exceeded the model's context limit."
    (= :rate-limit error-code) "Provider rate limit reached."
    (= :invalid-message-content error-code)
    "Provider rejected the message content format."
    (= :invalid-tool-schema error-code) "Provider rejected the tool schema."
    (= :missing-tool-output error-code) "Provider expected a missing tool output."
    (= 413 http_status)
    "Provider rejected the request as too large; the specific limit was not retained."
    (or (= :server error-class)
        (and (integer? http_status) (<= 500 http_status 599)))
    "Provider service failed while handling the request."
    (or (= :model-access error-class) (= 403 http_status))
    "Provider denied access to this resource."
    (or (= :rate-limit error-class) (= 429 http_status))
    "Provider rate limit reached."
    :else
    (or (get {:output-limit "Generation limit reached before a complete answer."
              :no-answer "Request completed without usable text."
              :unsupported-output "Request returned unsupported output."
              :incomplete-output "Provider returned incomplete output."
              :request-rejected "Provider rejected the request; a specific cause was not retained."
              :not-sent "Request was not sent; usage tracking or configuration was unavailable."}
             code)
        (when-let [label (progress/failure-labels code)]
          (when (not= :tool-error code) (str label ".")))
        "A specific failure reason was not retained.")))

(defn public-errors
  "Failure-first DTO fits the unchanged 64-node/4000-char core error budget.
   Three bounded rows, eight fields each, eight detail scalars at most. Full
   chronology/technical receipts remain in execution, not this error pane."
  [report]
  (let [failures (or (:failures report)
                    (filter #(not= :success (:outcome %)) (:calls report)))
        rows (take 3 (sort-by #(if (= :unknown (:outcome %)) 0 1) failures))
        total (if (:failure-counts report)
                (reduce + (vals (:failure-counts report))) (count failures))
        omitted (- total (count rows))]
    {:calls
     (mapv (fn [row]
             (let [label #(when (string? %) (progress/clip % 200))]
               (cond->
                 {:call (:call row) :tool (label (:tool row))
                  :operation (:operation row)
                  :provider (label (:provider row)) :model (label (:model row))
                  :reason (failure-reason row report)
                  :details (assoc (select-keys row
                                    [:code :error-class :error-code :parameter
                                     :output-cap :output_count :thinking_count])
                                  :outcome (:outcome row))}
                 (integer? (:http_status row))
                 (assoc :http_status (:http_status row))))) rows)
     :omitted omitted :trace_omitted (:omitted report)
     :truncated (boolean (or (pos? omitted) (:truncated report)))
     :reason (:reason report) :scope :unsuccessful-calls}))

(defn- failure-lines [report]
  (let [errors (public-errors report)]
    (when (seq (:calls errors))
      (str
        (string/join "\n"
          (map (fn [{:keys [call tool operation provider model reason]}]
                 (str "#" call " " (or provider tool "tool")
                      (when model (str "/" model))
                      (when operation (str " (" (name operation) ")"))
                      " — " reason)) (:calls errors)))
        (when (pos? (:omitted errors))
          (str "\n" (:omitted errors) " more unsuccessful calls omitted."))))))

(defn public-report [report]
  ;; Deliberately omit arguments and IDs; not a generic tool-payload viewer.
  (-> (select-keys report [:reason :budget :omitted :truncated])
      (assoc :calls
             (mapv #(select-keys % [:call :tool :outcome :attempted :confirmed
                                    :operation :provider :model :code :elapsed_ms
                                    :cost :finish :status :text-present :output-cap
                                    :input_count :output_count :thinking_count
                                    :http_status :error-class :error-code
                                    :parameter])
                   (:calls report)))))

(defn- trace-summary [report]
  (let [{:keys [limit used remaining requested]} (:budget report)
        header (str (get reasons (:reason report))
                    (when-let [label (progress/failure-labels (:code report))]
                      (str " " label ".")) "\n"
                    "Tool budget: total=" limit " used=" used
                    " remaining=" remaining " requested=" requested
                    ". Trace (redacted arguments; ordinal references, "
                    "not provider IDs):")
        rows (:calls report)]
    (loop [text header remaining-rows rows shown 0]
      (let [row (first remaining-rows)
            line (when row
                   (str "\n#" (:call row) " " (:tool row) " "
                        (name (:outcome row)) " "
                        (when-let [reason (progress/failure-labels (:code row))]
                          (str "(" reason ") "))
                        (when (integer? (:elapsed_ms row))
                          (str (:elapsed_ms row) "ms "))
                        (json/generate-string (:arguments row))))]
        (if (and row (<= (+ (count text) (count line)) 1550)
                 (<= (+ (alength (.getBytes ^String text "UTF-8"))
                        (alength (.getBytes ^String line "UTF-8"))) 5500))
          (recur (str text line) (next remaining-rows) (inc shown))
          (let [omitted (+ (:omitted report) (- (count rows) shown))]
            (str text
                 (when (pos? omitted)
                   (str "\n[" omitted " trace rows omitted from chat; "
                        "inspect tool-trace data,"
                        " which also marks any structured omissions.]"))
                 (when (empty? rows) "\nNo tool calls were attempted."))))))))

(defn- outcome-line [report answers]
  (let [counts (or (:failure-counts report)
                   (frequencies
                     (map outcome-kind
                          (remove #(= :success (:outcome %)) (:calls report)))))
        labels [[:limit "hit its output limit" "hit their output limit"]
                [:empty "returned no text" "returned no text"]
                [:unsupported "returned unsupported output"
                 "returned unsupported output"]
                [:incomplete "returned incomplete output"
                 "returned incomplete output"]
                [:rejected "request rejected" "requests rejected"]
                [:model-failed "model failed" "models failed"]
                [:tool-failed "tool failed" "tools failed"]
                [:unknown "unconfirmed" "unconfirmed"]
                [:not-run "not run" "not run"]]
        parts (concat
                (when (seq answers) [(str (count answers) " answered")])
                (for [[kind singular plural] labels
                      :let [n (get counts kind 0)] :when (pos? n)]
                  (str n " " (if (= 1 n) singular plural))))]
    (when (seq parts) (str (string/join " · " parts) "."))))

(defn summary
  ([report] (summary report []))
  ([report answers] (summary report answers nil))
  ([report answers parent-text]
   (if-not (or (seq answers)
               (some #(= :inference (:operation %))
                     (concat (:calls report) (:failures report))))
     (if parent-text
       (string/join "\n\n" (remove string/blank?
                             [parent-text (outcome-line report answers)
                              (failure-lines report)]))
       (trace-summary report))
     (string/join "\n\n"
       (remove string/blank?
         [(if (seq answers)
            (string/join "\n\n"
              (map (fn [{:keys [provider model text truncated]}]
                     (str text (when truncated " [shortened]")
                          "\n-# " provider "/" model)) answers))
            "No model answers returned.")
          (outcome-line report answers)
          (failure-lines report)
          (if (= :unknown-result (:reason report))
            (str "Stopped after an uncertain outcome. "
                 "Do not retry unconfirmed calls automatically.")
            (when-not (#{:partial-result :completed} (:reason report))
              (get reasons (:reason report))))
          (when (:truncated report)
            "Some execution details were omitted.")])))))
