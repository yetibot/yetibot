(ns yetibot.models.ai-command-tools
  "One literal command through the real interpreter, with private capture only.
   The root orchestration owns actor/toolset/budget admission and dataset storage."
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [instaparse.core :as insta]
            [robert.hooke :as hooke]
            [yetibot.core.handler :as handler]
            [yetibot.core.interpreter :as interp]
            [yetibot.core.parser :as parser]
            [yetibot.core.util.command :as command]
            [yetibot.core.util.public-result :as public]))

(def ^:private max-bytes (* 2 1024 1024))
(def ^:dynamic *capture* nil)
(def ^:dynamic *parent-ordinal* nil)

(defn- problem [code began?]
  {:stage :collection :field "command" :code code
   :remedy (case code
             :size "Reduce the requested data scope."
             :unknown-outcome "Do not retry automatically; verify the outcome."
             "Check the command and its authorized source scope.")
   :execution-began began?})

(defn- invalid! []
  (throw (ex-info "Invalid native command arguments."
                  {:ai/error true})))

(defn- parse! [text]
  (when-not (and (string? text) (<= 1 (count text) 4096)
                 (not (string/blank? text)))
    (invalid!))
  (let [text (or (second (command/extract-command text)) text)
        tree (parser/parser text)]
    (when (or (insta/failure? tree) (not= :expr (first tree))) (invalid!))
    tree))

(defn arguments! [_config operation args]
  (when-not (and (= "command" operation) (map? args)
                 (= #{"command"} (set (keys args))))
    (invalid!))
  (parse! (get args "command"))
  args)

(defn- elapsed [start]
  (long (/ (- (System/nanoTime) start) 1000000)))

(defn- bounded [value]
  (let [used (volatile! 0)]
    (letfn [(charge! [n]
              (when (> (vswap! used + n) max-bytes)
                (throw (ex-info "Capture bound exceeded." {:capture/bound true}))))
            (walk [v depth]
              (when (> depth 12)
                (throw (ex-info "Capture depth exceeded." {:capture/bound true})))
              (charge! 1)
              (cond
                (or (nil? v) (boolean? v) (keyword? v)) v
                (string? v) (do (when (> (count v) max-bytes) (charge! max-bytes))
                                (charge! (alength (.getBytes ^String v "UTF-8"))) v)
                (number? v)
                (if (and (not (ratio? v)) (Double/isFinite (double v)))
                  (do (charge! (count (str v))) v)
                  (throw (ex-info "Unsupported capture number." {:capture/bound true})))
                (map? v)
                (reduce-kv (fn [out k cell]
                             (when-not (or (keyword? k) (string? k))
                               (throw (ex-info "Unsupported capture key." {:capture/bound true})))
                             (charge! (count (str k)))
                             (assoc out k (walk cell (inc depth)))) {} v)
                (sequential? v)
                (loop [xs (seq v) out []]
                  (if-not xs out
                    (do (when (>= (count out) 10000) (charge! max-bytes))
                        (recur (next xs) (conj out (walk (first xs) (inc depth)))))))
                :else (throw (ex-info "Unsupported capture value." {:capture/bound true}))))]
      (let [out (walk value 0)
            n (alength (.getBytes ^String (json/generate-string out) "UTF-8"))]
        (when (> n max-bytes) (charge! max-bytes))
        [out n]))))

(defn- snapshot-pipeline [pipeline]
  (let [p (public/projection (:public-result pipeline))
        [out _] (bounded (cond-> (select-keys pipeline [:value :data :data-collection])
                          p (assoc :public-result p)))]
    (cond-> out
      p (update :public-result #(with-meta % (meta p))))))

(defn- unavailable [state]
  (-> state (assoc :available? false :bytes 0)
      (update :expressions
              #(mapv (fn [entry] (dissoc entry :pipeline :tree)) %))))

(defn capture-expression
  "Inactive pass-through hook; never dispatches or publishes a chat response.
   Host admission receives the exact four interpreter arguments for every entry."
  [callback chat-source user yetibot-user tree]
  (if-not *capture*
    (callback chat-source user yetibot-user tree)
    (let [{:keys [state context]} *capture*
          start (System/nanoTime)
          ordinal
          (locking state
            (when (or (:closed? @state) (>= (count (:expressions @state)) 256))
              (throw (ex-info "Command capture admission closed." {:ai/error true})))
            (when-not (and (= chat-source (:chat-source context))
                           (= user (:user context))
                           (= yetibot-user (:yetibot-user context)))
              (throw (ex-info "Command source changed." {:ai/error true})))
            (let [ordinal (inc (count (:expressions @state)))
                  entry (cond-> {:ordinal ordinal :status :unknown :elapsed-ms 0}
                          *parent-ordinal* (assoc :parent-ordinal *parent-ordinal*))]
              ;; Reserve fixed entry/key/timing overhead as well as charging
              ;; serialized trees and companions against the aggregate bound.
              (swap! state
                     (fn [s]
                       (let [s (update s :expressions conj entry)]
                         (if (<= (+ (:bytes s) 256) max-bytes)
                           (update s :bytes + 256) (unavailable s)))))
              (try
                (let [[tree n] (bounded tree)]
                  (swap! state
                         (fn [s]
                           (if (and (:available? s) (<= (+ (:bytes s) n) max-bytes))
                             (-> s (update :bytes + n)
                                 (assoc-in [:expressions (dec ordinal) :tree] tree))
                             (unavailable s)))))
                (catch Exception _ (swap! state unavailable)))
              ordinal))]
      (try
        ;; Host admission may fail or block. Never hold the closure lock while
        ;; invoking it, and recheck closure before the interpreter can start.
        ((:before-expression! context) chat-source user yetibot-user tree)
        (locking state
          (when (:closed? @state)
            (throw (ex-info "Command capture admission closed." {:ai/error true})))
          (swap! state assoc :began? true))
        (binding [*parent-ordinal* ordinal
                  interp/*chat-source* chat-source interp/*current-user* user
                  interp/*yetibot-user* yetibot-user]
          (let [result (callback chat-source user yetibot-user tree)
                status (if (:error result) :error :success)
                ;; Realize outside the lock: a lazy companion must not hold
                ;; timeout closure hostage. Late writes are refused below.
                snapshot (when (:available? @state)
                           (try
                             (let [pipeline (snapshot-pipeline result)
                                   [_ n] (bounded pipeline)]
                               [pipeline n])
                             (catch Exception ex
                               (if (:capture/bound (ex-data ex)) nil (throw ex)))))]
            (locking state
              (when-not (:closed? @state)
                (swap! state update-in [:expressions (dec ordinal)]
                       merge {:status status :elapsed-ms (elapsed start)})
                (when (:available? @state)
                  (let [[pipeline n] snapshot]
                    (swap! state
                           (fn [s]
                             (if (and snapshot (<= (+ (:bytes s) n) max-bytes))
                               (-> s (update :bytes + n)
                                   (assoc-in [:expressions (dec ordinal) :pipeline] pipeline))
                               (unavailable s))))))))
            result))
        (catch Throwable ex
          (locking state
            (when-not (:closed? @state)
              (swap! state update-in [:expressions (dec ordinal)] merge
                     {:status :error :elapsed-ms (elapsed start)})))
          (throw ex))))))

(hooke/add-hook #'handler/handle-parsed-expr #'capture-expression)

(defn execute!
  "Invoke once with host-held :chat-source/:user/:yetibot-user/:actor/:toolset
   and :before-expression!. The callback rechecks inherited policy and budgets;
   none of that authority is read from command arguments or returned data.
   Cancellation is not proof that a dispatched write stopped."
  [text {:keys [chat-source user yetibot-user actor toolset pipeline-input
                before-expression!] :as context}]
  (let [tree (parse! text)]
    (when-not (and (map? chat-source) (map? user) (map? yetibot-user)
                   (some? actor) (some? toolset) (fn? before-expression!))
      (throw (ex-info "Command execution requires host request context." {:ai/error true})))
    (let [start (System/nanoTime)
          state (atom {:closed? false :began? false :available? true
                       :bytes 0 :expressions []})
          capture {:state state :context context}
          task (future
                 (binding [*capture* capture *parent-ordinal* nil
                           interp/*pipeline-input* pipeline-input]
                   (try
                     (handler/handle-parsed-expr chat-source user yetibot-user tree)
                     :completed
                     (catch Throwable _ :failed))))
          outcome (deref task handler/expr-eval-timeout-ms :timeout)
          final (locking state
                  (swap! state assoc :closed? true)
                  @state)]
      (when (= :timeout outcome) (future-cancel task))
      (let [status (cond
                     (= :timeout outcome) (if (:began? final) :unknown :error)
                     (= :failed outcome) (if (:began? final) :unknown :error)
                     :else (or (get-in final [:expressions 0 :status]) :error))]
        {:status status
         :pipeline (when (:available? final) (get-in final [:expressions 0 :pipeline]))
         :elapsed-ms (elapsed start)
         :capture-available? (:available? final)
         :problems (cond-> []
                     (not (:available? final)) (conj (problem :size (:began? final)))
                     (not= :success status)
                     (conj (problem (if (= :unknown status) :unknown-outcome :incomplete-scope)
                                    (:began? final))))
         :expressions (:expressions final)}))))
