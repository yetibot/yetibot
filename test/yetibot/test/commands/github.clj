(ns yetibot.test.commands.github
  (:require
   [clojure.test :refer :all]
   [yetibot.commands.github :refer [stats-cmd]]
   [yetibot.api.github :as gh]))

(def dummy-poll-counter (atom 0))
(def mock-poll-result {:a 1 :d 1 :c 1 :con 4})
(def mock-repo-info ["wilfred" "tv"])

(defn mock-stats-fn [_ _]
  (if (= @dummy-poll-counter 2)
    mock-poll-result
    (swap! dummy-poll-counter inc)))

(deftest test-stats-cmd-polling-no-response
  (with-redefs-fn
    {#'gh/sum-stats (fn [_ _] nil)}
    #(is (= (stats-cmd {:match (cons "dummy" mock-repo-info)})
            (apply format
                   "Crunching the latest data for `%s/%s`, try again in a few moments 🐌"
                   mock-repo-info)))))

(deftest test-stats-cmd-polling-success-response
  (with-redefs-fn
    {#'gh/sum-stats mock-stats-fn}
    #(is (= (stats-cmd {:match (cons "dummy" mock-repo-info)})
            (apply format
                   "%s/%s: %s commits, %s additions, %s deletions, %s contributors"
             (into mock-repo-info (vals mock-poll-result)))))))

