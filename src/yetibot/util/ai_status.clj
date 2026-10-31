(ns yetibot.util.ai-status
  "Request-local typed status; core owns transport and terminal cleanup."
  (:require [yetibot.core.adapters.discord :as discord]
            [yetibot.models.ai-progress :as progress]))

(defn with-status
  "Fork typed host progress from the existing answer callback. Never finish
   core status here: the collector still has to flush the terminal answer."
  [target prepared complete]
  (let [accepted? (try
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
      (complete (cond-> prepared
                  (or accepted? parent) (assoc :on-progress callback)))
      (finally
        (locking state (swap! state assoc :open? false))))))
