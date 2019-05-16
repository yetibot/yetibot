(ns yetibot.test.commands.pagerduty
  (:require
   [midje.sweet :refer [fact =>]]
   [yetibot.core.util.command-info :refer [command-execution-info]]
   [yetibot.commands.pagerduty :refer :all]))

(fact
  "teams show subcommand matches"
  (let [{:keys [match matched-sub-cmd]}
        (command-execution-info "pagerduty teams show foo bar")]
    (last match) => "foo bar"
    matched-sub-cmd => #'teams-show-cmd))

(fact
  "teams matches the correct command"
  (let [{:keys [match matched-sub-cmd]}
        (command-execution-info "pagerduty teams foo bar")]
    (first match) => "teams foo bar"
    matched-sub-cmd => #'teams-cmd))

(fact
  "users matches the correct command"
  (let [{:keys [match matched-sub-cmd]}
        (command-execution-info "pagerduty users foo bar")]
    (first match) => "users foo bar"
    matched-sub-cmd => #'users-cmd))

(fact
  "schedules matches the correct command"
  (let [{:keys [match matched-sub-cmd]}
        (command-execution-info "pagerduty schedules foo bar")]
    (first match) => "schedules foo bar"
    matched-sub-cmd => #'schedules-cmd))

(fact
  "schedules show matches the correct command"
  (let [{:keys [match matched-sub-cmd]}
        (command-execution-info "pagerduty schedules show foo bar")]
    (first match) => "schedules show foo bar"
    matched-sub-cmd => #'schedules-show-cmd))
