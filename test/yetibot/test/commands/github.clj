(ns yetibot.test.commands.github
  (:require
   [midje.sweet :refer [fact => anything]]
   [yetibot.commands.github :refer [stats-cmd]]
   [yetibot.api.github :as gh]))

(def mock-poll-result {:a 1 :d 1 :c 1 :con 4})
(def mock-repo-info ["wilfred" "tv"])

(fact test-stats-cmd-polling-no-response
  (stats-cmd {:match (cons "dummy" mock-repo-info)}) =>
    {:result/error
     "Crunching the latest data for `wilfred/tv`, try again in a few moments 🐌"}
  (provided
    (gh/sum-stats anything anything) => nil))

(fact test-stats-cmd-polling-success-response
  (stats-cmd {:match (cons "dummy" mock-repo-info)}) =>
  {:result/data {:a 1 :c 1 :con 4 :d 1}
   :result/value "wilfred/tv: 1 commits, 1 additions, 1 deletions, 4 contributors"}
  (provided
    (gh/sum-stats anything anything) => mock-poll-result))
