(ns yetibot.test.commands.pagerduty
  (:require
   [midje.sweet :refer [fact =>]]
   [yetibot.core.util.command-info :refer [command-execution-info]]
   [yetibot.commands.pagerduty :refer :all]))

(fact
  "teams show subcommand matches"
  (let [{:keys [match matched-sub-cmd]}
        (command-execution-info "pagerduty teams show foo")]
    (last match) => "foo"
    matched-sub-cmd => #'teams-show-cmd))

(fact
  "teams matches the correct command"
  (let [{:keys [match matched-sub-cmd]}
        (command-execution-info "pagerduty teams foo")]
    (first match) => "teams foo"
    matched-sub-cmd => #'teams-cmd))

(fact
  "users matches the correct command"
  (let [{:keys [match matched-sub-cmd]}
        (command-execution-info "pagerduty users foo")]
    (first match) => "users foo"
    matched-sub-cmd => #'users-cmd))

(fact
  "schedules matches the correct command"
  (let [{:keys [match matched-sub-cmd]}
        (command-execution-info "pagerduty schedules foo")]
    (first match) => "schedules foo"
    matched-sub-cmd => #'schedules-cmd))

(fact
  "schedules show matches the correct command"
  (let [{:keys [match matched-sub-cmd]}
        (command-execution-info "pagerduty schedules show foo")]
    (first match) => "schedules show foo"
    matched-sub-cmd => #'schedules-show-cmd))

