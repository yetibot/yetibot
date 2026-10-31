(ns yetibot.test.models.ai-progress
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact facts =>]]))

(defn progress [function & args]
  (apply (requiring-resolve
           (symbol "yetibot.models.ai-progress" (name function))) args))

(defn host-event [call outcome]
  {:tool-event {:domain :host :call call :label "yetibot_help"
                :outcome outcome}})

(fact "ai-progress retains fast ordered rows, not raw answer or draft data"
  (let [state (reduce #(progress :advance %1 %2) (progress :initial-state)
                      [(host-event 1 :running) (host-event 1 :success)
                       (host-event 2 :running) (host-event 2 :error)
                       {:answer "PRIVATE" :summary "PRIVATE"
                        :text "PRIVATE" :phase "PRIVATE"}])
        text (progress :render state)]
    (mapv :outcome (:rows state)) => [:success :error]
    (mapv :call (:rows state)) => [1 2]
    (string/includes? text "#1 yetibot_help — success") => true
    (string/includes? text "#2 yetibot_help — error") => true
    (string/includes? (pr-str state) "PRIVATE") => false
    (progress :advance state (host-event 1 :running)) => state))

(facts "ai-progress bounds rows independently and preserves omitted uncertainty"
  (let [events (mapcat #(vector (host-event % :running)
                                (host-event % (if (odd? %) :error :unknown)))
                       (range 1 101))
        state (reduce #(progress :advance %1 %2) (progress :initial-state)
                      events)
        text (progress :render state)]
    (count (:rows state)) => 12
    (:omitted state) => 88
    (string/includes? text "88 rows omitted") => true
    (string/includes? text "error") => true
    (string/includes? text "unknown") => true
    (<= (count text) 1800) => true
    (= text (String. (.getBytes ^String text "UTF-8") "UTF-8")) => true))

(fact "ai-progress ignores malformed metadata and keeps native budget separate"
  (let [budget {:limit 6 :used 2 :remaining 4}
        initial (progress :initial-state)
        state (progress :advance initial {:tool-budget budget})
        native {:tool-event {:domain :native :response 2 :call 1
                             :label "web search" :outcome :success}}]
    (:budget (progress :advance state native)) => budget
    (string/includes? (progress :render state)
                      "Tools: 2/6 · 4 remaining") => true
    (progress :advance state {:tool-budget {:used 9 :limit 2 :remaining 0}})
    => state
    (progress :advance state {:tool-event {:domain :host :call "PRIVATE"
                                          :label "PRIVATE" :outcome :oops}})
    => state
    (progress :advance initial {:opaque "PRIVATE"}) => initial))

(fact "ai-progress rendering accepts only safe bounded fields, UTF16 boundary"
  (let [state (reduce #(progress :advance %1 %2) (progress :initial-state)
                      (for [n (range 1 14)]
                        {:tool-event {:domain :host :call n
                                      :label (apply str (repeat 64 "a"))
                                      :outcome :unknown}}))
        text (progress :render state)]
    (<= (count text) 1800) => true
    (progress :clip (str (string/join (repeat 1799 "a")) "😀") 1800)
    => (string/join (repeat 1799 "a"))
    (progress :clip (str (string/join (repeat 1798 "a")) "😀") 1800)
    => (str (string/join (repeat 1798 "a")) "😀")
    (string/includes? text "1 rows omitted") => true))
