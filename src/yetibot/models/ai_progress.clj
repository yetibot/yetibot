(ns yetibot.models.ai-progress
  "Pure bounded public status state; no provider data or delivery lifecycle."
  (:require [clojure.string :as string]))

(def redacted-label "[unavailable-or-redacted-tool]")
(def outcomes [:running :success :error :unknown :skipped :rejected])

(defn clip [^String text limit]
  (let [end (min (.length text) (max 0 limit))
        end (if (and (pos? end)
                     (Character/isHighSurrogate (.charAt text (dec end))))
              (dec end) end)]
    (subs text 0 end)))

(defn- ordinal? [value]
  (and (integer? value) (<= 1 value Integer/MAX_VALUE)))

(defn tool-event
  "Select typed public fields only. Labels must already be privacy checked."
  [{:keys [domain call response label outcome]}]
  (when (and (#{:host :native} domain) (ordinal? call)
             (some #{outcome} outcomes) (string? label)
             (if (= domain :host)
               (or (= label redacted-label)
                   (re-matches #"[a-zA-Z0-9_-]{1,64}" label))
               (and (ordinal? response)
                    (#{"web search" "X search"} label))))
    (cond-> {:domain domain :call call :label label :outcome outcome}
      (= domain :native) (assoc :response response))))

(defn tool-budget [{:keys [used limit remaining]}]
  (when (and (integer? used) (integer? limit) (integer? remaining)
             (<= 1 limit 20) (<= 0 used limit)
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
  (let [state (if-let [budget (tool-budget (:tool-budget event))]
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
  [{:keys [rows budget omitted omitted-outcomes]}]
  (clip
    (string/join
      "\n"
      (concat
        [(if budget
           (str "Tools: " (:used budget) "/" (:limit budget)
                " · " (:remaining budget) " remaining")
           "Working")]
        (map (fn [{:keys [domain response call label outcome]}]
               (str (when (= domain :native) (str "Search " response " "))
                    "#" call " " label " — " (name outcome))) rows)
        (when (pos? omitted)
          [(str omitted " rows omitted; outcomes seen: "
                (string/join ", " (map name (filter omitted-outcomes
                                                   outcomes))))])))
    1800))
