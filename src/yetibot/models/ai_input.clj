(ns yetibot.models.ai-input
  "Bounded, per-request piped data for parent AI messages; never execution."
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [yetibot.api.mcp :as mcp]))

(def error-message
  (str "Piped AI input is unsupported or too large. "
       "Select less data and try again."))

(defn- invalid! []
  (throw (ex-info error-message {:ai/error true :ai/piped-input true})))

(defn- size [text] (alength (.getBytes ^String text "UTF-8")))

(defn- spelling [value]
  (if (keyword? value) (subs (str value) 1) value))

(defn- incoming [args]
  ;; Aligned collection data wins over its unselected enclosing API response.
  (some (fn [field]
          (let [value (get args field)]
            (when (and (some? value) (not= "" value)) [field value])))
        [:data-collection :data :opts :raw]))

(defn capture
  "Snapshot only JSON-compatible data before async work. No config or IO.
   Raw snapshot: 64KiB, depth12, 2048 nodes, 128 entries/container."
  [args]
  (when-let [[field value] (incoming args)]
    (try
      (let [nodes (volatile! 0)
            bytes (volatile! 0)]
        (letfn [(text! [text]
                  (when (or (> (count text) 65536)
                            (> (vswap! bytes + (size text)) 65536))
                    (invalid!))
                  text)
                (walk [value depth]
                  (when (or (> depth 12) (> (vswap! nodes inc) 2048))
                    (invalid!))
                  (cond
                    (or (nil? value) (boolean? value)) value
                    (or (string? value) (keyword? value))
                    (text! (spelling value))
                    (number? value)
                    (do
                      (when-not
                        (and (not (ratio? value))
                             (Double/isFinite (double value))
                             (or (not (decimal? value))
                                 (and (<= (.precision ^BigDecimal value) 128)
                                      (<= -308 (.scale ^BigDecimal value)
                                          308))))
                        (invalid!))
                      (text! (str value))
                      value)
                    (map? value)
                    (do
                      (when (> (count value) 128) (invalid!))
                      (reduce-kv
                        (fn [out k v]
                          (let [k (walk k (inc depth))]
                            (when-not (and (string? k)
                                           (not (contains? out k)))
                              (invalid!))
                            (assoc out k (walk v (inc depth))))) {} value))
                    (sequential? value)
                    (loop [items (seq value) out []]
                      (if-not items out
                        (do
                          (when (>= (count out) 128) (invalid!))
                          (recur (next items)
                                 (conj out (walk (first items) (inc depth)))))))
                    :else (invalid!)))]
          (let [snapshot {:source "yetibot-pipeline" :untrusted true
                          :kind (name field) :data (walk value 0)}]
            (when (> (size (json/generate-string snapshot)) 65536)
              (invalid!))
            snapshot)))
      (catch Exception _ (invalid!)))))

(defn command-text
  "Remove the interpreter's appended display value, not user-written options.
   Explicit inline substitutions retain their existing prompt semantics."
  [{:keys [match raw]}]
  (if (and (string? raw) (not (string/blank? raw))
           (string/ends-with? match (str " " raw)))
    (subs match 0 (- (count match) (inc (count raw))))
    match))

(def sensitive-field
  (re-pattern
    (str "(?i)^(authorization|password|token|secret|key|api[-_]?key|"
         "key[-_]?file|account[-_]?id)$")))

(defn- sensitive? [k]
  (boolean (re-matches sensitive-field (last (string/split k #"/" -1)))))

(defn- sensitive-copies [data]
  ;; Aliases can embed the rendered value in the prompt. Mask copies there and
  ;; in sibling fields too. Literal replacement keeps large values out of regex.
  (->> (for [node (tree-seq coll? seq data) :when (map? node)
             [k v] node :when (sensitive? k)
             leaf (tree-seq coll?
                            #(if (map? %) (vals %) (seq %)) v)
             :when (or (string? leaf) (number? leaf))]
         (let [encoded (json/generate-string leaf)]
           (if (string? leaf)
             [leaf (subs encoded 1 (dec (count encoded)))] [encoded])))
       (apply concat)
       (remove string/blank?)
       distinct
       (sort-by count >)))

(defn- protect [value clean]
  (cond
    (string? value) (clean value)
    (map? value)
    (reduce-kv
      (fn [out k v]
        (let [safe-k (clean k)]
          (when (contains? out safe-k) (invalid!))
          (assoc out safe-k (if (sensitive? k)
                             "[redacted]" (protect v clean))))) {} value)
    (vector? value) (mapv #(protect % clean) value)
    :else (let [text (json/generate-string value)]
            (if (= text (clean text)) value "[redacted]"))))

(defn messages
  "Attach redacted JSON as untrusted user data before the current request.
   Final payload is <=12KiB; never silently truncate or inherit into children."
  [messages snapshot config]
  (if-not snapshot messages
    (try
      (let [copies (sensitive-copies (:data snapshot))
            clean #(mcp/metadata-redact
                     (reduce (fn [text secret]
                               (string/replace text secret "[redacted]"))
                             % copies) config)
            text (json/generate-string (update snapshot :data protect clean))]
        (when (> (size text) 12288) (invalid!))
        (conj (mapv #(update % :content clean) (pop (vec messages)))
              {:role "user" :content text}
              (update (last messages) :content clean)))
      (catch Exception _ (invalid!)))))
