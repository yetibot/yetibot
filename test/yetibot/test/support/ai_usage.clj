(ns yetibot.test.support.ai-usage
  (:require [yetibot.core.db :as db]
            [yetibot.core.db.ai-usage :as store]))

(defn with-ledger [f]
  (let [events (atom [])]
    (with-redefs
      [db/connected? (atom true)
       store/begin! (fn [event]
                      (swap! events conj (assoc event :state "pending")) [1])
       store/finish!
       (fn [id result]
         (swap! events
           #(mapv (fn [row]
                    (if (and (= id (:event-id row)) (= "pending" (:state row)))
                      (merge row result) row)) %)) [1])]
      (f events))))
