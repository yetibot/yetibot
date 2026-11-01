(ns yetibot.test.integration.ai-caption
  (:require [clojure.string :as string]
            [midje.sweet :refer [fact =>]]
            [yetibot.util.ai-status :as status]))

(def prepared {:model "sample-model" :actor {:adapter "discord"}
               :provider {:key "fixture-secret"}})

(fact "Discord captions are small, measured and public-safe"
  (status/caption prepared {:result/value "Answer"
                            :result/data {:mcp {:tool-calls 2}}} 1250)
  => {:result/value "Answer\n\n-# sample-model · 1.3s · 2 tools"
      :result/data {:mcp {:tool-calls 2}}}
  (:result/value (status/caption (assoc prepared :no-tools true)
                                  {:result/value "Answer"} 20))
  => "Answer\n\n-# sample-model · 0.0s · 0 tools")

(fact "unfinished fenced text cannot swallow the tiny footer"
  (:result/value (status/caption prepared {:result/value "```clj\n(+ 1 2)"} 0))
  => "```clj\n(+ 1 2)\n```\n\n-# sample-model · 0.0s"
  (:result/value
    (status/caption prepared {:result/value "````\n```\n````"} 0))
  => "````\n```\n````\n\n-# sample-model · 0.0s")

(fact "missing usage never becomes invented zero cost or tool usage"
  (:result/value (status/caption prepared {:result/value "Answer"} 1000))
  => "Answer\n\n-# sample-model · 1.0s"
  (string/includes? (:result/value
                      (status/caption prepared {:result/value "Answer"} 1))
                    "$") => false)

(fact "intermediate pipeline values are not decorated"
  (status/caption (assoc prepared :caption? false)
                  {:result/value "4"} 10) => {:result/value "4"})

(fact "Slack, structured answers and unsafe identities remain unchanged"
  (status/caption (assoc-in prepared [:actor :adapter] "slack")
                  {:result/value "Answer"} 10) => {:result/value "Answer"}
  (status/caption prepared {:result/value ["a" "b"]} 10)
  => {:result/value ["a" "b"]}
  (doseq [model ["fixture-secret" "@everyone\nprivate" "bad`model"]]
    (fact (status/caption (assoc prepared :model model)
                          {:result/value "Answer"} 10)
          => {:result/value "Answer"})))
