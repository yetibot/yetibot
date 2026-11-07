(ns yetibot.test.models.ai-limits
  (:require [midje.sweet :refer [fact facts => throws]]
            [yetibot.api.ai-outcome :as outcome]
            [yetibot.util.ai-limits :as limits]))

(defn isolated [f]
  (let [before @limits/runtime]
    (try
      (reset! limits/runtime {})
      (with-redefs-fn {#'limits/configured-defaults {}} f)
      (finally (reset! limits/runtime before)))))

(fact "built-in budgets are generous without any AI/provider configuration"
  (isolated #(fact (limits/effective {})
                   => {:max-output-tokens 32768 :max-tool-calls 20})))

(fact "flags override runtime values which override file and legacy defaults"
  (isolated
    (fn []
      (with-redefs-fn
        {#'limits/configured-defaults {:max-output-tokens "8192"
                                     :mcp {:max-tool-calls "4"}}}
        (fn []
        (limits/effective {}) => {:max-output-tokens 8192 :max-tool-calls 4}
        (let [checks (atom 0)]
          (limits/set-runtime! {:max-output-tokens 65536}
                               #(swap! checks inc))
          @checks => 2
          (limits/set-runtime! {:max-tool-calls 40} (fn []))
          @limits/runtime => {:max-output-tokens 65536 :max-tool-calls 40})
        (limits/effective {})
        => {:max-output-tokens 65536 :max-tool-calls 40}
        (limits/effective {:max-output-tokens 131072 :max-tool-calls 128})
        => {:max-output-tokens 131072 :max-tool-calls 128}
        (:effective (limits/reset-runtime! (fn [])))
        => {:generation_limit 8192 :tool_call_limit 4}
        @limits/runtime => {})))))

(fact "invalid or unauthorized partial updates do not mutate runtime defaults"
  (isolated
    (fn []
      (limits/set-runtime! {:max-output-tokens 65536} (fn []))
      (doseq [values [{:max-tool-calls 129} {:max-output-tokens 262145}
                     {:max-output-tokens nil} {:provider "private"}]]
        (fact (limits/set-runtime! values (fn [])) => (throws Exception)))
      (let [checks (atom 0)]
        (limits/set-runtime! {:max-tool-calls 128}
          #(when (= 2 (swap! checks inc))
             (throw (ex-info "authority changed" {})))) => (throws Exception))
      @limits/runtime => {:max-output-tokens 65536})))

(fact "larger generation caps remain in closed output-failure diagnostics"
  (doseq [cap [32768 65536 262144]]
    (fact (:output-cap (outcome/details {} "model" {}
                                       {:max-output-tokens cap})) => cap)))

(facts "strict numeric budget bounds do not admit malformed coercions"
  (doseq [[field maximum] [[:max-output-tokens 262144] [:max-tool-calls 128]]]
    (fact (limits/request! field maximum) => maximum)
    (doseq [value [0 -1 (inc maximum) "02" "+2" "1.0" "1e2" "PRIVATE"]]
      (fact (limits/request! field value) => (throws Exception)))))
