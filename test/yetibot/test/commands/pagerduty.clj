(ns yetibot.test.commands.pagerduty
  (:require
   [midje.sweet :refer [namespace-state-changes with-state-changes fact => truthy]]
   [yetibot.core.util.command-info :refer [command-execution-info]]
   [yetibot.commands.pagerduty :refer :all]))

(fact
  "teams show subcommand matches"
  (let [{:keys [match matched-sub-cmd]} (command-execution-info
                                          "pagerduty teams show foo")]
    (last match) => "foo"
    matched-sub-cmd => #'teams-show-cmd
    ))
