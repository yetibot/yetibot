(ns yetibot.test.commands.github
  (:require
   [midje.sweet :refer [fact => anything]]
   [yetibot.commands.github :refer [stats-cmd]]
   [yetibot.api.github :as gh]))

(def mock-poll-result {:a 1 :d 1 :c 1 :con 4})
(def mock-repo-info ["wilfred" "tv"])

(fact test-stats-cmd-polling-no-response
  (stats-cmd {:match (cons "dummy" mock-repo-info)}) =>
      (apply format "Crunching the latest data for `%s/%s`, try again in a few moments 🐌"
             mock-repo-info)
  (provided
    (gh/sum-stats anything anything) => nil))

(fact test-stats-cmd-polling-success-response
  (stats-cmd {:match (cons "dummy" mock-repo-info)}) =>
          (apply format "%s/%s: %s commits, %s additions, %s deletions, %s contributors"
           (into mock-repo-info (vals mock-poll-result)))
  (provided
    (gh/sum-stats anything anything) => mock-poll-result))
