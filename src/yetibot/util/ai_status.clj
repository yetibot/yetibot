(ns yetibot.util.ai-status
  "Request-local typed status; core owns transport and terminal cleanup."
  (:require [clojure.string :as string]
            [yetibot.api.mcp :as mcp]
            [yetibot.core.adapters.discord :as discord]
            [yetibot.models.ai-progress :as progress]
            [yetibot.util.ai-execution :as execution]
            [yetibot.util.ai-cost :as cost]))

(defn discord? [prepared]
  (= "discord" (or (get-in prepared [:actor :adapter])
                    (some-> execution/*event* :chat-source :adapter name))))

(defn- close-fence [text]
  (let [open (reduce
               (fn [open [_ marker tail]]
                 (if open
                   (if (and (= (first open) (first marker))
                            (>= (count marker) (count open))
                            (string/blank? tail)) nil open)
                   marker))
               nil (re-seq #"(?m)^ {0,3}(`{3,}|~{3,})([^\n]*)$" text))]
    (str text (when open (str "\n" open)))))

(defn caption
  "Discord-only public execution metadata. Unknown cost is explicitly n/a."
  [prepared result elapsed-ms]
  (let [model (:model prepared)
        secrets (concat (:additional-secrets prepared)
                        [(get-in prepared [:provider :key])
                         (get-in prepared [:provider :key-file])])
        tools (get-in result [:result/data :mcp :tool-calls]
                      (when (:no-tools prepared) 0))]
    (if (and (discord? prepared) (not (false? (:caption? prepared)))
             (string? (:result/value result))
             (string? model) (re-matches #"[A-Za-z0-9@._:/-]{1,200}" model)
             (= model (mcp/metadata-redact model {:secrets secrets})))
      (update result :result/value #(str (close-fence %) "\n\n-# "
              (string/join " · "
                (cond-> [model]
                  (some? elapsed-ms)
                  (conj (String/format java.util.Locale/ROOT "%.1fs"
                          (to-array [(/ elapsed-ms 1000.0)])))
                  (and (integer? tools) (<= 0 tools 100000))
                  (conj (str tools " tool" (when (not= 1 tools) "s")))
                  true (conj (cost/label
                               (get-in result [:result/data :ai/cost])))))))
      result)))

(defn with-status
  "Fork typed host progress from the existing answer callback. Never finish
   core status here: the collector still has to flush the terminal answer."
  [target prepared complete]
  (let [started (System/nanoTime)
        accepted? (try
                    (= :accepted (discord/start-command-status!
                                   target "Working"))
                    (catch Exception _ false))
        parent (:on-progress prepared)
        state (atom {:open? true :progress (progress/initial-state)})
        callback
        (bound-fn [event]
          (locking state
            (when (:open? @state)
              (when parent (try (parent event) (catch Exception _ nil)))
              (when accepted?
                (try
                  (let [next-state (progress/advance
                                     (:progress @state)
                                     (select-keys event
                                                  [:tool-event :tool-budget]))]
                    (swap! state assoc :progress next-state)
                    (discord/update-command-status!
                      (progress/render next-state)))
                  (catch Exception _ nil))))))]
    (try
      (let [result (complete (cond-> prepared
                               (or accepted? parent)
                               (assoc :on-progress callback)))]
        ;; Live Grok already publishes its answer through the parent callback.
        ;; Changing that final text would defeat its duplicate-send guard.
        (if parent result
          (caption prepared result
                   (/ (- (System/nanoTime) started) 1000000.0))))
      (finally
        (locking state (swap! state assoc :open? false))))))
