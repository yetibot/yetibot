(ns ^{:doc (clojure.core/str
             "Request-local, privacy-first tool failure reports. "
             "Never stores raw calls/results.")}
  yetibot.models.ai-tool-trace
  (:refer-clojure :exclude [bytes])
  (:require [cheshire.core :as json]
            [yetibot.api.mcp :as mcp]))

(def reasons
  {:malformed-batch
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

(defn report [state limit reason]
  (let [{:keys [used requested calls omitted]} @state]
    {:reason reason :budget {:limit limit :used used
                             :remaining (max 0 (- limit used))
                             :requested requested}
     :calls calls :omitted omitted :truncated (pos? omitted)}))

(defn fail! [reason]
  (throw (ex-info (get reasons reason) {::reason reason})))

(defn summary [report]
  (let [{:keys [limit used remaining requested]} (:budget report)
        header (str (get reasons (:reason report)) "\n"
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
