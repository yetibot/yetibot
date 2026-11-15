(ns yetibot.models.ai-progress
  "Pure bounded public status state; no provider data or delivery lifecycle."
  (:require [clojure.string :as string]))

(def redacted-label "[unavailable-or-redacted-tool]")
(def outcomes [:running :success :error :unknown :skipped :rejected])
(def failure-labels
  {:output-limit "output limit; no complete answer"
   :no-answer "no usable answer"
   :unsupported-output "unsupported output"
   :incomplete-output "incomplete output"
   :request-rejected "provider rejected request"
   :zen-rate-limit "Zen rate limit"
   :zen-free-usage-limit "Zen free-usage limit reached"
   :zen-access-denied "Zen denied access"
   :not-sent "request not sent"
   :unknown-outcome "delivery uncertain; not retried"
   :tool-error "tool returned an error"
   :invalid-arguments "invalid tool arguments"
   :credential-input "credential-bearing input blocked"
   :configuration-unavailable "provider configuration unavailable"
   :credentials-unavailable "provider credentials unavailable"
   :untrusted-source "trusted chat request required"
   :policy-denied "operation denied by toolset policy"
   :model-ineligible "model is not eligible for text chat"
   :discovery-excluded "provider excluded from automatic discovery"
   :model-capacity-exceeded "generation allowance exceeds advertised model capacity; request not sent"
   :routing-unavailable "model routing metadata unavailable"
   :catalog-invalid "provider returned invalid model metadata"
   :catalog-unavailable "could not reach the provider's model catalog"
   :conversation-unavailable "conversation context unavailable"})

(defn clip [^String text limit]
  (let [end (min (.length text) (max 0 limit))
        end (if (and (pos? end)
                     (Character/isHighSurrogate (.charAt text (dec end))))
              (dec end) end)]
    (subs text 0 end)))

(def ^:private problem-fields
  #{"*" "action" "operation" "provider" "model" "prompt" "input" "input_json"
    "images" "images.*" "question" "questions" "questions.*" "questions.*.type"
    "questions.*.instructions" "questions.*.criteria" "questions.*.criteria.*"
    "fields" "fields.*" "fields.*.*" "questions.*.*" "fields.*.type"
    "fields.*.instructions" "fields.*.unit"
    "fields.*.decimals" "fields.*.items" "fields.*.min" "fields.*.max"
    "limit" "page" "offset" "before_id" "after" "after_id" "count" "path" "path.*"
    "max_output_tokens" "category" "pricing" "separator" "query" "name" "topic"
    "view" "since" "location" "unit" "account" "days" "emoji" "id" "version"
    "schedule" "command" "template_id" "top" "bottom" "type" "source" "format"
    "options" "options.*" "dataset" "dataset_id" "kind" "x" "y" "series" "title"})

(def ^:private problem-remedies
  {:required "Supply the required field."
   :type "Use the field type shown in the tool schema."
   :enum "Choose an advertised value from the tool schema."
   :range "Use a value within the field bounds in the tool schema."
   :size "Reduce the field size to the tool schema limit."
   :format "Use the field format shown in the tool schema."
   :unknown-field "Remove unsupported arguments."
   :unavailable-field "Choose a field from the dataset descriptor."
   :incompatible-unit "Use a quantitative field with consistent, explicit units."
   :incomplete-scope "Use complete authoritative observations and distinguish each series."
   :credential-input "Remove credentials from the request."
   :unknown-outcome "Do not retry automatically; verify the outcome."
   :invalid-arguments "Check the arguments against the tool schema."})

(def ^:private expected-remedies
  {:object "Use an object." :array "Use an array." :string "Use a string."
   :integer "Use an integer." :number "Use a finite number." :boolean "Use a boolean."
   :scalar "Use a string, finite number or boolean option value."
   :source-bytes "Use nonblank source of at most 262144 UTF-8 bytes."
   :option-string "Use an option string of at most 2048 characters."
   :options-count "Use at most 32 scalar diagram options."
   :option-name "Use an option name starting with a letter, then letters, digits, _ or - (at most 64 characters)."
   :integer-0-6 "Use an integer from 0 through 6."
   :integer-0-127 "Use an integer from 0 through 127."
   :integer-0-128 "Use an integer from 0 through 128."
   :integer-1-20 "Use an integer from 1 through 20."
   :integer-1-50 "Use an integer from 1 through 50."
   :integer-1-365 "Use an integer from 1 through 365."
   :integer-1-32768 "Use an integer from 1 through 32768."
   :integer-1-262144 "Use an integer from 1 through 262144."
   :integer-0-2147483647 "Use an integer from 0 through 2147483647."
   :integer-1-2147483647 "Use an integer from 1 through 2147483647."
   :render-format "Choose auto, png, svg, jpeg, pdf, txt, utxt or base64."
   :engine-name "Use an engine name starting with a lowercase letter, then lowercase letters, digits, _ or - (at most 64 characters)."})

(defn sanitize-problem
  "Closed host diagnostic vocabulary shared by trace and typed progress.
   Remedies are reconstructed, never copied from rejected content."
  [{:keys [stage field code expected execution-began]}]
  (when (and (#{:arguments :collection :chart :render :delivery} stage)
             (contains? problem-fields field) (contains? problem-remedies code)
             (boolean? execution-began))
    (let [expected (when (contains? expected-remedies expected) expected)]
      (cond-> {:stage stage :field field :code code
               :remedy (or (get expected-remedies expected) (get problem-remedies code))
               :execution-began execution-began}
        expected (assoc :expected expected)))))

(defn problem-text [problem]
  (when-let [{:keys [stage field remedy execution-began]} (sanitize-problem problem)]
    (str (string/capitalize (name stage)) " validation failed; "
         (if execution-began "execution began. " "execution did not begin. ")
         field ": " remedy)))

(defn stage-timings [entries]
  (when (sequential? entries)
    (->> (take 5 entries)
         (keep (fn [{:keys [stage elapsed-ms]}]
                 (when (and (#{:planning :collection :preparation :render :handoff} stage)
                            (integer? elapsed-ms) (<= 0 elapsed-ms 300000))
                   {:stage stage :elapsed-ms elapsed-ms})))
         (reduce (fn [rows row]
                   (if (some #(= (:stage row) (:stage %)) rows) rows (conj rows row))) [])
         vec)))

(defn timing-details [event]
  (cond-> {}
    (sequential? (:stage-timings event))
    (assoc :stage-timings (stage-timings (:stage-timings event)))
    (and (integer? (:planning-responses event))
         (<= 0 (:planning-responses event) 1024))
    (assoc :planning-responses (:planning-responses event))))

(defn- ordinal? [value]
  (and (integer? value) (<= 1 value Integer/MAX_VALUE)))

(defn tool-event
  "Select typed public fields only. Labels must already be privacy checked."
  [{:keys [domain call response label outcome code problem]}]
  (when (and (#{:host :native} domain) (ordinal? call)
             (some #{outcome} outcomes) (string? label)
             (if (= domain :host)
               (or (= label redacted-label)
                   (re-matches #"[a-zA-Z0-9_-]{1,64}" label))
               (and (ordinal? response)
                    (#{"web search" "X search"} label))))
    (cond-> {:domain domain :call call :label label :outcome outcome}
      (= domain :native) (assoc :response response)
      (contains? failure-labels code) (assoc :code code)
      (sanitize-problem problem) (assoc :problem (sanitize-problem problem)))))

(defn tool-budget [{:keys [used limit remaining]}]
  (when (and (integer? used) (integer? limit) (integer? remaining)
             (<= 1 limit 128) (<= 0 used limit)
             (= remaining (- limit used)))
    {:used used :limit limit :remaining remaining}))

(defn initial-state []
  {:rows [] :budget nil :omitted 0 :omitted-outcomes #{}
   :last-host 0 :last-native [0 0]})

(defn- identity-key [{:keys [domain response call]}]
  [domain response call])

(defn advance
  "Reduce sanitized events in source order. Omitted outcomes are observations,
   not invented terminal results; storage stays bounded even for native storms."
  [state event]
  (let [state (merge state (timing-details event))
        state (if-let [budget (tool-budget (:tool-budget event))]
                (assoc state :budget budget) state)]
    (if-let [row (tool-event (:tool-event event))]
      (let [key (identity-key row)
            index (first (keep-indexed
                           #(when (= key (identity-key %2)) %1)
                           (:rows state)))
            native? (= :native (:domain row))
            field (if native? :last-native :last-host)
            ordinal (if native? [(:response row) (:call row)] (:call row))
            new? (pos? (compare ordinal (get state field)))
            state (if new? (assoc state field ordinal) state)]
        (cond
          (some? index)
          (if (= :running (get-in state [:rows index :outcome]))
            (assoc-in state [:rows index] row) state)
          (and new? (< (count (:rows state)) 12))
          (update state :rows conj row)
          :else
          (cond-> (update state :omitted-outcomes conj (:outcome row))
            new? (update :omitted inc))))
      state)))

(defn render
  "At most 1800 UTF16 units, with independently capped rows and omissions."
  [{:keys [rows budget omitted omitted-outcomes stage-timings]}]
  (clip
    (string/join
      "\n"
      (concat
        [(if budget
           (str "Tools: " (:used budget) "/" (:limit budget)
                " · " (:remaining budget) " remaining")
           "Working")]
        (map (fn [{:keys [domain response call label outcome code problem]}]
               (str (when (= domain :native) (str "Search " response " "))
                    "#" call " " label " — " (name outcome)
                    (when-let [reason (failure-labels code)]
                      (str " (" reason ")"))
                    (when-let [text (problem-text problem)] (str " — " text)))) rows)
        (when (seq stage-timings)
          [(string/join " · " (map #(str (name (:stage %)) " " (:elapsed-ms %) "ms") stage-timings))])
        (when (pos? omitted)
          [(str omitted " rows omitted; outcomes seen: "
                (string/join ", " (map name (filter omitted-outcomes
                                                   outcomes))))])))
    1800))
